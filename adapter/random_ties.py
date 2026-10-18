"""Null controls on the VALIDATION maps.

--mode ties (default): ties broken uniformly at random within the TIE SET (rl.AdapterEnv:
  the base pick plus later equal-score legal actions). Gives the "harmless reshuffling"
  null and the list-order ablation (base result minus null mean). Unchanged from the
  2026-10-16 version: same RNG draws, so earlier numbers reproduce exactly.

--mode offtie: the MATCHED null for one-hot adapter checkpoints, whose deviations are all
  outside the tie set. At every decision that has at least one legal action outside the
  tie set, with probability --rate the base pick is replaced by a uniformly random such
  action; otherwise the base pick is kept. Each line reports the REALISED off-tie rate
  (over all decisions), which is the number to match against eval_adapter.py's
  "outside the tie set" figure. --rates sweeps several rates.

Usage:
  python adapter\random_ties.py [--seeds 10]
  python adapter\random_ties.py --mode offtie --rates 0.005,0.01,0.02,0.04,0.08 --seeds 5
Validation only. Never point this at the test maps.
"""
import argparse, glob, os, sys, time

import numpy as np
import jpype

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ppo_adapter import Slot, C, A, P, DEFAULT_BASE, ROOT  # noqa: E402
from eval_adapter import VALIDATION_MAPS  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--mode", choices=["ties", "offtie"], default="ties")
    p.add_argument("--rate", type=float, default=0.01,
                   help="offtie: override probability per decision that has an off-tie option")
    p.add_argument("--rates", default=None, help="offtie: comma list of rates (overrides --rate)")
    p.add_argument("--seeds", type=int, default=10)
    p.add_argument("--first-seed", type=int, default=1)
    p.add_argument("--opponents", default="WorkerRush,WorkerRushPlusPlus,WorkerDefense")
    p.add_argument("--maxcycles", type=int, default=4000)
    a = p.parse_args()

    os.chdir(ROOT)
    cp = [os.path.join(ROOT, "out")]
    cp += glob.glob(os.path.join(ROOT, "lib", "*.jar"))
    cp += glob.glob(os.path.join(ROOT, "lib", "bots", "*.jar"))
    jpype.startJVM(classpath=cp)
    AdapterEnv = jpype.JClass("rl.AdapterEnv")
    JIntArray = jpype.JArray(jpype.JInt)
    opps = a.opponents.split(",")

    env = AdapterEnv(a.base, a.maxcycles)
    slot = Slot(env, None, 0.0, JIntArray)
    obs = np.zeros((1, C, P, P), np.float32)
    mask = np.zeros((1, P * P, A), bool)
    tie = np.zeros((1, P * P, A), bool)
    bse = np.full((1, P * P), -1, np.int64)

    if a.mode == "ties":
        rates = [None]
    else:
        rates = [float(x) for x in a.rates.split(",")] if a.rates else [a.rate]

    title = "Random tie-breaking (null control)" if a.mode == "ties" else "Random off-tie overrides (matched null)"
    print(f"\n=== {title}, validation maps ===\nbase  : {a.base}"
          f"\nopps  : {', '.join(opps)}\nseeds : {a.first_seed}..{a.first_seed + a.seeds - 1}"
          + ("" if a.mode == "ties" else f"\nrates : {', '.join(str(r) for r in rates)}") + "\n")
    t0 = time.perf_counter()
    summary = []
    for rate in rates:
        if rate is not None:
            print(f"--- rate {rate}")
        wins, realised = [], []
        for s in range(a.first_seed, a.first_seed + a.seeds):
            rng = np.random.default_rng(s)
            totW = totT = totL = 0
            dec = changed = eligible = 0
            per = []
            for opp in opps:
                nW = nT = nL = 0
                for m in VALIDATION_MAPS:
                    for side in (0, 1):
                        env.reset(m, opp, side)
                        while not env.done():
                            slot.read(obs, mask, bse, 0, tie)
                            d = bse >= 0
                            if rate is None:
                                g = rng.gumbel(size=tie.shape)
                                act = np.where(tie, g, -np.inf).argmax(-1)
                            else:
                                off = mask & ~tie
                                has = off.any(-1) & d
                                flip = has & (rng.random(has.shape) < rate)
                                g = rng.gumbel(size=off.shape)
                                rnd = np.where(off, g, -np.inf).argmax(-1)
                                act = np.where(flip, rnd, np.maximum(bse, 0))
                                eligible += int(has.sum())
                            dec += int(d.sum())
                            changed += int(((act != bse) & d).sum())
                            h, w = env.height(), env.width()
                            codes = act[0].reshape(P, P)[:h, :w].reshape(-1).astype(np.int32)
                            codes[np.asarray(env.baseCode(), dtype=np.int32) < 0] = -1
                            env.step(JIntArray(codes))
                        wnr = int(env.winner())
                        if wnr == side: nW += 1
                        elif wnr == -1: nT += 1
                        else: nL += 1
                totW += nW; totT += nT; totL += nL
                per.append(f"{opp} {nW}-{nT}-{nL}")
            wins.append(totW)
            pct = 100.0 * changed / max(1, dec)
            realised.append(pct)
            extra = "" if rate is None else f" (eligible {100.0 * eligible / max(1, dec):.1f}%)"
            print(f"seed {s:3d}: TOTAL {totW}W {totT}T {totL}L  [{'; '.join(per)}]  "
                  f"changed {pct:.2f}% of {dec} decisions{extra}", flush=True)
        wv = np.array(wins)
        line = (f"wins over {len(wv)} seeds: mean {wv.mean():.1f}, sd {wv.std(ddof=1) if len(wv) > 1 else 0:.1f}, "
                f"min {wv.min()}, median {np.median(wv):.1f}, max {wv.max()}; realised change {np.mean(realised):.2f}%")
        print(line + "\n", flush=True)
        summary.append(("ties" if rate is None else f"rate {rate}") + ": " + line)
    print("=== summary ===")
    for s in summary:
        print(s)
    print(f"{time.perf_counter() - t0:.0f}s")


if __name__ == "__main__":
    main()