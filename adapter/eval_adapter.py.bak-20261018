"""Evaluate an adapter checkpoint -- or the bare base bot if no --ckpt -- with ARGMAX on the
VALIDATION maps against the scripted panel, in gp.Bench's format.

Deterministic opponents: one game per map x side (16 distinct games).
Stochastic opponents (RandomAI, RandomBiasedAI, RandomBiasedSingleUnitAI): --samples games
per map x side.

Sanity check: with no --ckpt the adapter is zero, argmax == base action everywhere (in both
onehot and ties mode, since --pick-bonus > 0), and the results must match gp.Bench for the
base bot exactly (e.g. 5242 pruned vs WorkerRush: 10W 2T 4L,
8x8B:LL 8x8D:WW 8x8F:WW 8x8G:LW 8x8K:WT 16x16:WL 16x16C:WW 16x16E:WT).

Base distribution and pick bonus come from the checkpoint's args (older checkpoints have
none and evaluate as onehot). Validation is for choosing tau and checkpoints. Never point
this at the test maps.
"""
import argparse, csv, glob, os, sys, time

import numpy as np
import torch
import jpype

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ppo_adapter import Adapter, Slot, base_logits, C, A, P, DEFAULT_BASE, ROOT  # noqa: E402

VALIDATION_MAPS = [
    "maps/8x8/basesWorkers8x8B.xml", "maps/8x8/basesWorkers8x8D.xml", "maps/8x8/basesWorkers8x8F.xml",
    "maps/8x8/basesWorkers8x8G.xml", "maps/8x8/basesWorkers8x8K.xml", "maps/16x16/basesWorkers16x16.xml",
    "maps/16x16/basesWorkers16x16C.xml", "maps/16x16/basesWorkers16x16E.xml",
]
STOCHASTIC = {"RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI"}


def greedy(net, obs, base, mask, tau, ties=None, bonus=0.0):
    adj, _ = net(obs)
    decide = base >= 0
    legal = mask.clone()
    legal[..., 0] |= ~decide
    return (base_logits(base, tau, ties, bonus, adj.dtype) + adj).masked_fill(~legal, -1e9).argmax(-1)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ckpt", default=None, help="adapter .pt; omit to evaluate the bare base bot")
    p.add_argument("--base", default=None, help="override the base .ind (default: from ckpt)")
    p.add_argument("--tau", type=float, default=None, help="override tau (default: from ckpt)")
    p.add_argument("--base-dist", choices=["onehot", "ties"], default=None,
                   help="override the base distribution (default: from ckpt, else onehot)")
    p.add_argument("--pick-bonus", type=float, default=None, help="override the pick bonus (default: from ckpt, else 1.0)")
    p.add_argument("--opponents", default=None, help="comma list; default: eval.Panel.FULL_PANEL")
    p.add_argument("--samples", type=int, default=5)
    p.add_argument("--maxcycles", type=int, default=4000)
    a = p.parse_args()

    os.chdir(ROOT)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if a.ckpt:
        ck = torch.load(a.ckpt, map_location=device, weights_only=False)
        targs = ck["args"]
        tau = a.tau if a.tau is not None else targs["tau"]
        base = a.base or targs["base"]
        bdist = a.base_dist or targs.get("base_dist", "onehot")
        bonus = a.pick_bonus if a.pick_bonus is not None else targs.get("pick_bonus", 1.0)
        net = Adapter(targs["width"]).to(device)
        net.load_state_dict(ck["net"])
        label = os.path.basename(a.ckpt)[:-3]
        out_dir = os.path.dirname(os.path.abspath(a.ckpt))
    else:
        tau = a.tau if a.tau is not None else 0.1
        base = a.base or DEFAULT_BASE
        bdist = a.base_dist or "onehot"
        bonus = a.pick_bonus if a.pick_bonus is not None else 1.0
        net = Adapter().to(device)
        label = f"base-only-{bdist}"
        out_dir = os.path.join(ROOT, "results", "adapter")
    net.eval()
    use_ties = bdist == "ties"

    cp = [os.path.join(ROOT, "out")]
    cp += glob.glob(os.path.join(ROOT, "lib", "*.jar"))
    cp += glob.glob(os.path.join(ROOT, "lib", "bots", "*.jar"))
    jpype.startJVM(classpath=cp)
    AdapterEnv = jpype.JClass("rl.AdapterEnv")
    JIntArray = jpype.JArray(jpype.JInt)
    opps = a.opponents.split(",") if a.opponents else [str(x) for x in jpype.JClass("eval.Panel").FULL_PANEL]

    env = AdapterEnv(base, a.maxcycles)
    slot = Slot(env, None, 0.0, JIntArray)
    obs = np.zeros((1, C, P, P), np.float32)
    mask = np.zeros((1, P * P, A), bool)
    tie = np.zeros((1, P * P, A), bool)
    bse = np.full((1, P * P), -1, np.int64)

    def tens(x):
        return torch.from_numpy(x).to(device)

    print(f"\n=== Adapter eval (argmax, validation maps) ===\nckpt  : {a.ckpt or '(none: base only)'}"
          f"\nbase  : {base}\ntau   : {tau}\ndist  : {bdist}" + (f" (pick bonus {bonus:g})" if use_ties else "")
          + f"\nmaps  : {', '.join(os.path.basename(m)[12:-4] for m in VALIDATION_MAPS)}\n")
    rows, decisions, overrides, off_tie, tied = [], 0, 0, 0, 0
    totW = totT = totL = 0
    t0 = time.perf_counter()
    for opp in opps:
        reps = a.samples if opp in STOCHASTIC else 1
        W = T = L = 0
        grid = []
        for m in VALIDATION_MAPS:
            cell = ""
            for side in (0, 1):
                for _ in range(reps):
                    env.reset(m, opp, side)
                    while not env.done():
                        slot.read(obs, mask, bse, 0, tie)
                        with torch.no_grad():
                            act = greedy(net, tens(obs), tens(bse), tens(mask), tau,
                                         tens(tie) if use_ties else None, bonus).cpu().numpy()
                        dec = bse >= 0
                        decisions += int(dec.sum())
                        overrides += int(((act != bse) & dec).sum())
                        in_tie = np.take_along_axis(tie, act[..., None], -1)[..., 0]
                        off_tie += int((~in_tie & dec).sum())
                        tied += int(((tie.sum(-1) > 1) & dec).sum())
                        h, w = env.height(), env.width()
                        codes = act[0].reshape(P, P)[:h, :w].reshape(-1).astype(np.int32)
                        codes[np.asarray(env.baseCode(), dtype=np.int32) < 0] = -1
                        env.step(JIntArray(codes))
                    wnr = int(env.winner())
                    res = "W" if wnr == side else ("T" if wnr == -1 else "L")
                    W += res == "W"; T += res == "T"; L += res == "L"
                    cell += res
                    rows.append({"opponent": opp, "map": m, "side": side, "result": res, "cycles": int(env.time())})
            grid.append(f"{os.path.basename(m)[12:-4]}:{cell}")
        n = W + T + L
        totW += W; totT += T; totL += L
        print(f"vs {opp:<25} {W:3d}W {T:3d}T {L:3d}L  ({100.0 * W / n:3.0f}%)")
        print("    " + " ".join(grid), flush=True)

    nd = max(1, decisions)
    print(f"\nTOTAL {totW}W {totT}T {totL}L.  argmax differs from base on {100.0 * overrides / nd:.2f}% "
          f"of {decisions} decisions (outside the tie set: {100.0 * off_tie / nd:.2f}%); "
          f"tied decisions {100.0 * tied / nd:.1f}%.  {time.perf_counter() - t0:.0f}s")
    path = os.path.join(out_dir, f"eval-{label}-validation-{time.strftime('%Y%m%d-%H%M%S')}.csv")
    with open(path, "w", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=["opponent", "map", "side", "result", "cycles"])
        wr.writeheader()
        wr.writerows(rows)
    print("wrote", path)


if __name__ == "__main__":
    main()