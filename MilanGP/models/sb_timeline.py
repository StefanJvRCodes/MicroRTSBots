#!/usr/bin/env python3
"""Best score per generation of one structure-based run, with explore / exploit phases shaded.

Usage:
    python3 sb_timeline.py [metrics.jsonl] [output.png|.pdf|.svg]
"""

import json
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

EXPLORE, EXPLOIT = "#7b2cbf", "#0096c7"   # same violet / blue as the tree figures
INK, MUTED, GRID = "#1b1b1b", "#5c5a55", "#e6e4df"


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "../runs/sbgp-16h/run-3/metrics.jsonl"
    out = sys.argv[2] if len(sys.argv) > 2 else "figures/structure_timeline.png"
    rows = [json.loads(line) for line in open(path)]

    # A resumed run repeats generations; keep the last entry for each.
    by_gen = {r["generation"]: r for r in rows}
    gens = sorted(by_gen)
    best = [by_gen[g]["bestCombat"] for g in gens]

    plt.rcParams.update({"font.family": "sans-serif", "font.size": 13})
    fig, ax = plt.subplots(figsize=(11, 4.2))

    start = gens[0]
    for i, g in enumerate(gens):
        last = i == len(gens) - 1
        if last or by_gen[gens[i + 1]]["phase"] != by_gen[g]["phase"]:
            colour = EXPLORE if by_gen[g]["phase"] == "explore" else EXPLOIT
            ax.axvspan(start - 0.5, g + 0.5, color=colour, alpha=0.16, lw=0)
            if not last:
                start = gens[i + 1]

    ax.plot(gens, best, color=INK, lw=2.2, solid_capstyle="round")
    ax.set_xlim(gens[0] - 0.5, gens[-1] + 0.5)
    ax.set_xticks([])
    ax.set_xlabel("Training →", color=MUTED)
    ax.set_ylabel("Best score", color=MUTED)
    ax.grid(axis="y", color=GRID, lw=1)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=MUTED, length=0)
    ax.legend(handles=[Patch(color=EXPLORE, alpha=0.35, label="Explore"),
                       Patch(color=EXPLOIT, alpha=0.35, label="Exploit")],
              loc="upper left", frameon=False, ncol=2, labelcolor=INK)

    fig.tight_layout()
    fig.savefig(out, dpi=300, facecolor="white")
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
