"""Tie-set invariants and headroom, on TRAINING maps, playing the base codes.

Checks, for every deciding unit: the base code is in its tie set, and the tie set is a
subset of its legal codes (except the base code itself, for the NONE fallback).
Reports, per base-action kind: decisions, share with a tie set > 1 (the adapter's
headroom in ties mode), and mean tie-set size.

Usage: python adapter\tie_check.py [opponents, comma list; default WorkerRush,PassiveAI]
"""
import glob, os, sys
from collections import Counter

import numpy as np
import jpype

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ppo_adapter import TRAIN_MAPS, DEFAULT_BASE, ROOT, A  # noqa: E402


def kind(c):
    return ("NONE" if c == 0 else "MOVE" if c <= 4 else "HARVEST" if c <= 8 else
            "RETURN" if c <= 12 else "PRODUCE" if c <= 36 else "ATTACK")


def main():
    os.chdir(ROOT)
    cp = [os.path.join(ROOT, "out")]
    cp += glob.glob(os.path.join(ROOT, "lib", "*.jar"))
    cp += glob.glob(os.path.join(ROOT, "lib", "bots", "*.jar"))
    jpype.startJVM(classpath=cp)
    env = jpype.JClass("rl.AdapterEnv")(DEFAULT_BASE, 4000)
    JInt = jpype.JArray(jpype.JInt)
    opps = sys.argv[1].split(",") if len(sys.argv) > 1 else ["WorkerRush", "PassiveAI"]

    dec, tied, size = Counter(), Counter(), Counter()
    missing_base = outside_legal = games = 0
    for opp in opps:
        for m in TRAIN_MAPS:
            for side in (0, 1):
                env.reset(m, opp, side)
                games += 1
                while not env.done():
                    base = np.asarray(env.baseCode(), dtype=np.int64)
                    mask = np.asarray(env.mask(), dtype=np.int8).reshape(-1, A) != 0
                    tie = np.asarray(env.tieMask(), dtype=np.int8).reshape(-1, A) != 0
                    for cell in np.nonzero(base >= 0)[0]:
                        b = int(base[cell])
                        t = tie[cell]
                        if not t[b]:
                            missing_base += 1
                        extra = t & ~mask[cell]
                        extra[b] = False
                        if extra.any():
                            outside_legal += 1
                        n = int(t.sum())
                        k = kind(b)
                        dec[k] += 1
                        size[k] += n
                        if n > 1:
                            tied[k] += 1
                    env.step(JInt(base.astype(np.int32)))

    print(f"\n{games} games, opponents {','.join(opps)}, base {DEFAULT_BASE}")
    print(f"invariants: base missing from tie set {missing_base}, tie set outside legal {outside_legal}"
          f"  -> {'OK' if missing_base == 0 and outside_legal == 0 else 'FAILED'}\n")
    print(f"{'base action':<10} {'decisions':>10} {'tied':>8} {'mean tie size':>14}")
    for k in ["NONE", "MOVE", "HARVEST", "RETURN", "PRODUCE", "ATTACK"]:
        if dec[k]:
            print(f"{k:<10} {dec[k]:>10} {100.0 * tied[k] / dec[k]:>7.1f}% {size[k] / dec[k]:>14.2f}")
    tot = sum(dec.values())
    print(f"{'ALL':<10} {tot:>10} {100.0 * sum(tied.values()) / max(1, tot):>7.1f}% "
          f"{sum(size.values()) / max(1, tot):>14.2f}")
    print("\njava:", env.diagnostics())


if __name__ == "__main__":
    main()