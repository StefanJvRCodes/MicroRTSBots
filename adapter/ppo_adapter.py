"""Adapter-RL training (Jin, Slabaugh & Lucas, arXiv:2311.11537) on a frozen evolved bot.

Per idle unit:  p(a) = softmax( onehot(base_action)/tau + adj(s) )  over its legal codes.
The base agent is the frozen evolved bot, played through rl.AdapterEnv (Java, via JPype).
PPO as in the paper: gamma 0.99, GAE lambda 0.95, clip 0.2, value coef 1, Adam lr 2.5e-4,
8192 samples per iteration by default (8 envs x 1024 decision steps).

Departures from the paper (state them in the write-up):
  - fully convolutional adapter with per-cell action logits (GridNet style) instead of
    CNN -> MLP, so one network serves 8x8 and 16x16; boards are padded to 16x16 and the
    padding is marked as wall;
  - ONE adapter trained across all TRAINING maps and opponents (the paper trains one per
    map), because we evaluate on held-out maps;
  - joint action = one code per idle unit; a step's log-prob is the sum over deciding units.

The last policy layer is zero-initialised, so iteration 0 IS the base bot. Watch the
override rate (fraction of decisions where the sampled action differs from the base one):
with tau = 0.1 it starts near 0.05%, and if it never rises the adapter is not exploring.

Training touches TRAINING maps and opponents only (mirrors config/microrts-stage7.params).
"""
import argparse, csv, glob, json, os, time
from collections import defaultdict, deque

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import jpype

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TRAIN_MAPS = [
    "maps/8x8/basesWorkers8x8.xml", "maps/8x8/basesWorkers8x8C.xml", "maps/8x8/basesWorkers8x8E.xml",
    "maps/16x16/basesWorkers16x16B.xml", "maps/16x16/basesWorkers16x16D.xml",
    "maps/16x16/basesWorkers16x16G.xml", "maps/16x16/basesWorkers16x16F.xml",
    "maps/16x16/basesWorkers16x16H.xml",
]
TRAIN_OPPONENTS = ["PassiveAI", "RandomBiasedAI", "WorkerRush", "WorkerRushPlusPlus",
                   "WorkerDefense", "LightRush"]
SHORT = {"PassiveAI": "Pas", "RandomBiasedAI": "RBA", "WorkerRush": "WR",
         "WorkerRushPlusPlus": "WR++", "WorkerDefense": "WD", "LightRush": "LR"}

C, A, P = 29, 86, 16          # obs channels, action codes, padded board side
C_WALL = 18
DEFAULT_BASE = "results/best-seed5242-20261012-160727-pruned.ind"


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--tau", type=float, default=0.1)
    p.add_argument("--envs", type=int, default=8)
    p.add_argument("--steps", type=int, default=1024, help="decision steps per env per iteration")
    p.add_argument("--iters", type=int, default=500)
    p.add_argument("--lr", type=float, default=2.5e-4)
    p.add_argument("--gamma", type=float, default=0.99)
    p.add_argument("--lam", type=float, default=0.95)
    p.add_argument("--clip", type=float, default=0.2)
    p.add_argument("--vf-coef", type=float, default=1.0)
    p.add_argument("--ent-coef", type=float, default=0.01)
    p.add_argument("--epochs", type=int, default=4)
    p.add_argument("--minibatches", type=int, default=8)
    p.add_argument("--shaping", type=float, default=0.5, help="weight on the change in evalNow")
    p.add_argument("--maxcycles", type=int, default=4000)
    p.add_argument("--width", type=int, default=64)
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--save-every", type=int, default=10)
    p.add_argument("--run", default=None)
    p.add_argument("--smoke", action="store_true", help="tiny run to check everything works")
    a = p.parse_args()
    if a.smoke:
        a.envs, a.steps, a.iters, a.save_every = 4, 128, 3, 1
    return a


class Adapter(nn.Module):
    """Fully convolutional actor-critic: per-cell adjustment logits + scalar value."""

    def __init__(self, width=64):
        super().__init__()
        self.enc = nn.Sequential(
            nn.Conv2d(C, width, 3, padding=1), nn.ReLU(),
            nn.Conv2d(width, width, 3, padding=1), nn.ReLU(),
            nn.Conv2d(width, width, 3, padding=1), nn.ReLU())
        self.glob = nn.Linear(width, width)          # global context broadcast to every cell
        self.mix = nn.Conv2d(width, width, 3, padding=1)
        self.pi = nn.Conv2d(width, A, 1)
        nn.init.zeros_(self.pi.weight)               # adj = 0 at start -> pure base policy
        nn.init.zeros_(self.pi.bias)
        self.v = nn.Sequential(nn.Linear(2 * width, 256), nn.ReLU(), nn.Linear(256, 1))

    def forward(self, x):
        f = self.enc(x)
        f = F.relu(self.mix(f + self.glob(f.mean((2, 3)))[:, :, None, None]))
        adj = self.pi(f).permute(0, 2, 3, 1).reshape(x.shape[0], P * P, A)
        v = self.v(torch.cat([f.mean((2, 3)), f.amax((2, 3))], 1)).squeeze(1)
        return adj, v


def policy(net, obs, base, mask, tau, actions=None):
    """Combined distribution of the paper's Eq. 3, masked to legal codes, per cell."""
    adj, value = net(obs)
    decide = base >= 0
    onehot = F.one_hot(base.clamp(min=0), A).to(adj.dtype) * decide.unsqueeze(-1)
    legal = mask.clone()
    legal[..., 0] |= ~decide                         # dummy legal code where no unit decides
    logits = (onehot / tau + adj).masked_fill(~legal, -1e9)
    dist = torch.distributions.Categorical(logits=logits)
    if actions is None:
        actions = dist.sample()
    d = decide.to(adj.dtype)
    logp = (dist.log_prob(actions) * d).sum(1)
    ent = (dist.entropy() * d).sum(1) / d.sum(1).clamp(min=1)
    return actions, logp, ent, value


class Slot:
    """One AdapterEnv plus the bookkeeping for its current game."""

    def __init__(self, env, rng, shaping, jint):
        self.env, self.rng, self.shaping, self.jint = env, rng, shaping, jint

    def new_game(self):
        self.map = str(self.rng.choice(TRAIN_MAPS))
        self.opp = str(self.rng.choice(TRAIN_OPPONENTS))
        self.side = int(self.rng.integers(2))
        self.env.reset(self.map, self.opp, self.side)
        self.prev_eval = float(self.env.evalNow())

    def read(self, obs, mask, base, i):
        env = self.env
        h, w = env.height(), env.width()
        assert h <= P and w <= P, f"map larger than {P}x{P}"
        obs[i] = 0.0
        obs[i, :, :h, :w] = np.asarray(env.obs(), dtype=np.float32).reshape(C, h, w)
        obs[i, C_WALL, h:, :] = 1.0
        obs[i, C_WALL, :, w:] = 1.0
        mask[i] = False
        mask[i].reshape(P, P, A)[:h, :w] = np.asarray(env.mask(), dtype=np.int8).reshape(h, w, A) != 0
        base[i] = -1
        base[i].reshape(P, P)[:h, :w] = np.asarray(env.baseCode(), dtype=np.int64).reshape(h, w)

    def act(self, actions):
        env = self.env
        h, w = env.height(), env.width()
        a = actions.reshape(P, P)[:h, :w].reshape(-1).astype(np.int32)
        a[np.asarray(env.baseCode(), dtype=np.int32) < 0] = -1
        env.step(self.jint(a))
        ev = float(env.evalNow())
        r = self.shaping * (ev - self.prev_eval)
        self.prev_eval = ev
        if not env.done():
            return r, False, None
        wnr = int(env.winner())
        res = "W" if wnr == self.side else ("D" if wnr == -1 else "L")
        r += {"W": 1.0, "D": 0.0, "L": -1.0}[res]
        info = (self.opp, res, int(env.time()))
        self.new_game()
        return r, True, info


def main():
    args = parse_args()
    os.chdir(ROOT)
    rng = np.random.default_rng(args.seed)
    torch.manual_seed(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    cp = [os.path.join(ROOT, "out")]
    cp += glob.glob(os.path.join(ROOT, "lib", "*.jar"))
    cp += glob.glob(os.path.join(ROOT, "lib", "bots", "*.jar"))
    jpype.startJVM(classpath=cp)
    AdapterEnv = jpype.JClass("rl.AdapterEnv")
    JIntArray = jpype.JArray(jpype.JInt)

    stamp = time.strftime("%Y%m%d-%H%M%S")
    run = args.run or f"{os.path.basename(args.base)[:-4]}-tau{args.tau:g}-s{args.seed}-{stamp}"
    if args.smoke:
        run = "smoke-" + run
    out = os.path.join(ROOT, "results", "adapter", run)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "args.json"), "w") as fh:
        json.dump(vars(args), fh, indent=2)
    print(f"run {run}\ndevice {device}, {args.envs} envs x {args.steps} steps = "
          f"{args.envs * args.steps} samples/iter, tau {args.tau} (base logit {1 / args.tau:g})")

    E, T = args.envs, args.steps
    slots = [Slot(AdapterEnv(args.base, args.maxcycles), rng, args.shaping, JIntArray) for _ in range(E)]
    cur_obs = np.zeros((E, C, P, P), np.float32)
    cur_mask = np.zeros((E, P * P, A), bool)
    cur_base = np.full((E, P * P), -1, np.int64)
    for i, s in enumerate(slots):
        s.new_game()
        s.read(cur_obs, cur_mask, cur_base, i)

    net = Adapter(args.width).to(device)
    opt = torch.optim.Adam(net.parameters(), lr=args.lr, eps=1e-5)

    obs_b = np.zeros((T, E, C, P, P), np.float32)
    mask_b = np.zeros((T, E, P * P, A), bool)
    base_b = np.full((T, E, P * P), -1, np.int64)
    act_b = np.zeros((T, E, P * P), np.int64)
    lp_b = np.zeros((T, E), np.float32)
    val_b = np.zeros((T, E), np.float32)
    rew_b = np.zeros((T, E), np.float32)
    done_b = np.zeros((T, E), np.float32)

    recent = defaultdict(lambda: deque(maxlen=50))
    episodes, total_steps = 0, 0
    log_path = os.path.join(out, "log.csv")
    fields = ["iter", "steps", "sps", "episodes", "win", "draw", "loss", "override", "entropy",
              "pg_loss", "v_loss", "kl", "clipfrac"] + [f"win_{SHORT[o]}" for o in TRAIN_OPPONENTS]
    logf = open(log_path, "w", newline="")
    logw = csv.DictWriter(logf, fieldnames=fields)
    logw.writeheader()

    def tens(x):
        return torch.from_numpy(x).to(device)

    def save(tag):
        path = os.path.join(out, f"adapter-{tag}.pt")
        torch.save({"net": net.state_dict(), "args": vars(args), "iter": tag}, path)
        return path

    it = 0
    try:
        for it in range(1, args.iters + 1):
            t0 = time.perf_counter()
            n_dec = n_over = 0
            for t in range(T):
                obs_b[t], mask_b[t], base_b[t] = cur_obs, cur_mask, cur_base
                with torch.no_grad():
                    a, lp, _, v = policy(net, tens(cur_obs), tens(cur_base), tens(cur_mask), args.tau)
                a_np = a.cpu().numpy()
                act_b[t], lp_b[t], val_b[t] = a_np, lp.cpu().numpy(), v.cpu().numpy()
                dec = cur_base >= 0
                n_dec += int(dec.sum())
                n_over += int(((a_np != cur_base) & dec).sum())
                for i, s in enumerate(slots):
                    r, d, info = s.act(a_np[i])
                    rew_b[t, i], done_b[t, i] = r, float(d)
                    if info:
                        episodes += 1
                        recent[info[0]].append(info[1])
                        recent["ALL"].append(info[1])
                    s.read(cur_obs, cur_mask, cur_base, i)
            t_roll = time.perf_counter() - t0
            total_steps += T * E

            with torch.no_grad():
                _, _, _, next_v = policy(net, tens(cur_obs), tens(cur_base), tens(cur_mask), args.tau)
            next_v = next_v.cpu().numpy()
            adv = np.zeros((T, E), np.float32)
            last = np.zeros(E, np.float32)
            for t in reversed(range(T)):
                nv = next_v if t == T - 1 else val_b[t + 1]
                nonterm = 1.0 - done_b[t]
                delta = rew_b[t] + args.gamma * nv * nonterm - val_b[t]
                last = delta + args.gamma * args.lam * nonterm * last
                adv[t] = last
            ret = adv + val_b

            B = T * E
            f_obs, f_mask = obs_b.reshape(B, C, P, P), mask_b.reshape(B, P * P, A)
            f_base, f_act = base_b.reshape(B, P * P), act_b.reshape(B, P * P)
            f_lp, f_adv, f_ret = lp_b.reshape(B), adv.reshape(B), ret.reshape(B)
            mb = max(1, B // args.minibatches)
            idx = np.arange(B)
            st = defaultdict(list)
            for _ in range(args.epochs):
                rng.shuffle(idx)
                for s0 in range(0, B, mb):
                    j = idx[s0:s0 + mb]
                    _, nlp, ent, nv = policy(net, tens(f_obs[j]), tens(f_base[j]), tens(f_mask[j]),
                                             args.tau, tens(f_act[j]))
                    logratio = nlp - tens(f_lp[j])
                    ratio = logratio.exp()
                    ad = tens(f_adv[j])
                    ad = (ad - ad.mean()) / (ad.std() + 1e-8)
                    pg = torch.max(-ad * ratio, -ad * ratio.clamp(1 - args.clip, 1 + args.clip)).mean()
                    vl = 0.5 * ((nv - tens(f_ret[j])) ** 2).mean()
                    loss = pg - args.ent_coef * ent.mean() + args.vf_coef * vl
                    opt.zero_grad()
                    loss.backward()
                    nn.utils.clip_grad_norm_(net.parameters(), 0.5)
                    opt.step()
                    with torch.no_grad():
                        st["kl"].append(((ratio - 1) - logratio).mean().item())
                        st["clip"].append(((ratio - 1).abs() > args.clip).float().mean().item())
                        st["pg"].append(pg.item())
                        st["v"].append(vl.item())
                        st["ent"].append(ent.mean().item())
            t_all = time.perf_counter() - t0

            def rate(key, res):
                q = recent[key]
                return 100.0 * sum(1 for x in q if x == res) / len(q) if q else float("nan")

            row = {"iter": it, "steps": total_steps, "sps": round(T * E / t_roll), "episodes": episodes,
                   "win": rate("ALL", "W"), "draw": rate("ALL", "D"), "loss": rate("ALL", "L"),
                   "override": 100.0 * n_over / max(1, n_dec), "entropy": np.mean(st["ent"]),
                   "pg_loss": np.mean(st["pg"]), "v_loss": np.mean(st["v"]),
                   "kl": np.mean(st["kl"]), "clipfrac": np.mean(st["clip"])}
            for o in TRAIN_OPPONENTS:
                row[f"win_{SHORT[o]}"] = rate(o, "W")
            logw.writerow(row)
            logf.flush()
            per_opp = " ".join(f"{SHORT[o]} {row['win_' + SHORT[o]]:.0f}" for o in TRAIN_OPPONENTS)
            print(f"it {it:4d} | {total_steps:8d} steps | {row['sps']:5d} sps rollout, {t_all:5.1f}s/iter | "
                  f"eps {episodes:5d} | W/D/L {row['win']:.0f}/{row['draw']:.0f}/{row['loss']:.0f} "
                  f"[{per_opp}] | override {row['override']:.3f}% | ent {row['entropy']:.4f} "
                  f"| v {row['v_loss']:.3f} | kl {row['kl']:.4f} clip {row['clipfrac']:.3f}", flush=True)
            if it % args.save_every == 0:
                save(f"{it:04d}")
    except KeyboardInterrupt:
        print("interrupted")
    finally:
        print("saved", save(f"final-{it:04d}"))
        logf.close()


if __name__ == "__main__":
    main()