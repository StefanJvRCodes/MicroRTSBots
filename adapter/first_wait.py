"""Rule-based ablation: the base bot, but at cycle 0 every base unit whose base action is
NONE trains a worker instead. Tests what the adapter's dominant override on SBGP 5242
(NONE -> PRODUCE:Worker at t=0, the tree's cycle-0 wait) actually contributes.

--sides both|0|1|none   which side(s) get the fix; 'none' reproduces the base (regression).
--dir first|up|right|down|left
    first: the first legal PRODUCE:Worker in list order (what the base picks at cycle 1);
    a direction: produce in that direction, only where it is legal (else keep the base action).
Grid cells show per-side override counts, e.g. 8x8B:LW[1/1].
Validation only. Never point this at the test maps.
"""
import argparse, glob, os, sys, time

import numpy as np
import jpype

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ppo_adapter import Slot, C, A, P, DEFAULT_BASE, ROOT  # noqa: E402
from eval_adapter import VALIDATION_MAPS, STOCHASTIC  # noqa: E402

DIRS = ["up", "right", "down", "left"]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--sides", choices=["both", "0", "1", "none"], default="both")
    p.add_argument("--dir", choices=["first"] + DIRS, default="first")
    p.add_argument("--opponents", default="WorkerRush,WorkerRushPlusPlus,WorkerDefense")
    p.add_argument("--samples", type=int, default=5)
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

    print(f"\n=== No cycle-0 wait (rule ablation), validation maps ===\nbase  : {a.base}"
          f"\nsides : {a.sides}\ndir   : {a.dir}\n")
    totW = totT = totL = 0
    t0 = time.perf_counter()
    for opp in opps:
        reps = a.samples if opp in STOCHASTIC else 1
        W = T = L = 0
        grid = []
        for m in VALIDATION_MAPS:
            cell, ovs = "", []
            for side in (0, 1):
                apply = a.sides == "both" or a.sides == str(side)
                n_ov = 0
                for _ in range(reps):
                    env.reset(m, opp, side)
                    while not env.done():
                        slot.read(obs, mask, bse, 0, tie)
                        act = np.maximum(bse, 0).copy()
                        if apply and int(env.time()) == 0:
                            pw = mask[0, :, 13:17]
                            if a.dir == "first":
                                sel = (bse[0] == 0) & pw.any(-1)
                                pick = 13 + pw.argmax(-1)
                            else:
                                d = DIRS.index(a.dir)
                                sel = (bse[0] == 0) & pw[:, d]
                                pick = np.full(pw.shape[0], 13 + d)
                            if sel.any():
                                act[0, sel] = pick[sel]
                                n_ov += int(sel.sum())
                        h, w = env.height(), env.width()
                        codes = act[0].reshape(P, P)[:h, :w].reshape(-1).astype(np.int32)
                        codes[np.asarray(env.baseCode(), dtype=np.int32) < 0] = -1
                        env.step(JIntArray(codes))
                    wnr = int(env.winner())
                    res = "W" if wnr == side else ("T" if wnr == -1 else "L")
                    W += res == "W"; T += res == "T"; L += res == "L"
                    cell += res
                ovs.append(n_ov)
            grid.append(f"{os.path.basename(m)[12:-4]}:{cell}[{ovs[0]}/{ovs[1]}]")
        n = W + T + L
        totW += W; totT += T; totL += L
        print(f"vs {opp:<25} {W:3d}W {T:3d}T {L:3d}L  ({100.0 * W / n:3.0f}%)")
        print("    " + " ".join(grid), flush=True)
    print(f"\nTOTAL {totW}W {totT}T {totL}L.  {time.perf_counter() - t0:.0f}s")


if __name__ == "__main__":
    main()