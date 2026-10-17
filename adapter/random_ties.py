"""Null control and list-order ablation: the base bot with ties broken uniformly at random.

At every decision the code is drawn uniformly from the unit's TIE SET (rl.AdapterEnv:
the base pick plus later equal-score legal actions) instead of taking the list-order
first. With one tie-set member this is the base pick, so only list-order decisions
change. Run over several RNG seeds on the VALIDATION maps, it gives:
  - the null distribution for adapter checkpoints ("harmless reshuffling" of ties);
  - the list-order ablation: base result minus the null mean is how much of the
    validation result engine action order supplied.

Usage: python adapter\random_ties.py [--seeds 10] [--opponents WorkerRush,...]
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

    print(f"\n=== Random tie-breaking (null control), validation maps ===\nbase  : {a.base}"
          f"\nopps  : {', '.join(opps)}\nseeds : {a.first_seed}..{a.first_seed + a.seeds - 1}\n")
    wins = []
    t0 = time.perf_counter()
    for s in range(a.first_seed, a.first_seed + a.seeds):
        rng = np.random.default_rng(s)
        totW = totT = totL = 0
        dec = changed = 0
        per = []
        for opp in opps:
            nW = nT = nL = 0
            for m in VALIDATION_MAPS:
                for side in (0, 1):
                    env.reset(m, opp, side)
                    while not env.done():
                        slot.read(obs, mask, bse, 0, tie)
                        g = rng.gumbel(size=tie.shape)
                        act = np.where(tie, g, -np.inf).argmax(-1)
                        d = bse >= 0
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
        print(f"seed {s:3d}: TOTAL {totW}W {totT}T {totL}L  [{'; '.join(per)}]  "
              f"changed {100.0 * changed / max(1, dec):.2f}% of {dec} decisions", flush=True)

    wv = np.array(wins)
    print(f"\nwins over {len(wv)} seeds: mean {wv.mean():.1f}, sd {wv.std(ddof=1) if len(wv) > 1 else 0:.1f}, "
          f"min {wv.min()}, median {np.median(wv):.1f}, max {wv.max()}.  {time.perf_counter() - t0:.0f}s")


if __name__ == "__main__":
    main()