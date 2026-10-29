#!/usr/bin/env python3
"""Poster charts from the run-7 evaluation (results/*.txt, GPPlay output) and the batch summaries.

Usage:
    python3 results_charts.py [out_dir] [--batch ../runs/sbgp-16h] [--best run-7]

Writes results_sets, results_opponents, results_maps, results_runs and results_winrate_time as PNG and PDF.
"""

import argparse
import glob
import os
import re
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

WIN, TIE, LOSS = "#0096c7", "#d9d6cf", "#c8553d"
INK, MUTED, GRID = "#1b1b1b", "#5c5a55", "#e6e4df"
UNSEEN = "#7b2cbf"
AVERAGE = "#00658a"   # deeper shade of the win blue, so the average stands out from the runs

SETS = [  # (file prefix, label)
    ("A", "Seen opponents\nseen maps"),
    ("B", "Unseen opponents\nseen maps"),
    ("C", "Seen opponents\nunseen maps"),
    ("D", "Unseen opponents\nunseen maps"),
]
MATCHUP = re.compile(r"^(\S+) \| vs (\S+)\s*: (\d+)W (\d+)T (\d+)L")


def load(results_dir):
    """{set letter: [(map, opponent, w, t, l)]}"""
    out = {}
    for prefix, _ in SETS:
        path = glob.glob(os.path.join(results_dir, f"{prefix}_*.txt"))[0]
        rows = []
        for line in open(path):
            m = MATCHUP.match(line)
            if m:
                rows.append((m[1].split("/")[-1].removesuffix(".xml"), m[2], *map(int, m.groups()[2:])))
        out[prefix] = rows
    return out


def style():
    plt.rcParams.update({"font.family": "sans-serif", "font.size": 13, "axes.edgecolor": GRID,
                         "xtick.color": MUTED, "ytick.color": INK, "axes.labelcolor": MUTED})


def save(fig, out_dir, name):
    fig.tight_layout()
    for ext in ("png", "pdf"):
        path = os.path.join(out_dir, f"{name}.{ext}")
        fig.savefig(path, dpi=300, facecolor="white")
        print(f"Wrote {path}")
    plt.close(fig)


def stacked(ax, labels, wtl, unseen=()):
    """100% horizontal win / tie / loss bars, top to bottom in the given order."""
    ys = range(len(labels))[::-1]
    for y, (w, t, l) in zip(ys, wtl):
        n = w + t + l
        left = 0
        for value, colour in ((w, WIN), (t, TIE), (l, LOSS)):
            frac = value / n
            ax.barh(y, frac, left=left, color=colour, height=0.68, edgecolor="white", linewidth=2)
            left += frac
        ax.text(w / n - 0.012, y, f"{w / n:.0%}", va="center", ha="right", color="white",
                fontsize=12, fontweight="bold") if w / n > 0.12 else None
    ax.set_yticks(list(ys), labels)
    for tick, label in zip(ax.get_yticklabels(), labels):
        if label in unseen:
            tick.set_color(UNSEEN)
            tick.set_fontweight("bold")
    ax.set_xlim(0, 1)
    ax.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0))
    ax.tick_params(length=0)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.legend(handles=[matplotlib.patches.Patch(color=c, label=s) for c, s in ((WIN, "Win"), (TIE, "Tie"), (LOSS, "Loss"))],
              loc="lower left", bbox_to_anchor=(0, 1.0), ncol=3, frameon=False, fontsize=12)


def sets_chart(data, out_dir):
    labels, wtl = [], []
    for prefix, label in SETS:
        rows = data[prefix]
        wtl.append(tuple(sum(r[i] for r in rows) for i in (2, 3, 4)))
        labels.append(label)
    labels.append("All games")
    wtl.append(tuple(sum(x[i] for x in wtl) for i in range(3)))
    fig, ax = plt.subplots(figsize=(10, 4.6))
    stacked(ax, labels, wtl)
    ax.get_yticklabels()[-1].set_fontweight("bold")
    save(fig, out_dir, "results_sets")


def grouped_chart(data, key, unseen_sets, out_dir, name, height):
    totals = defaultdict(lambda: [0, 0, 0])
    unseen = set()
    for prefix, rows in data.items():
        for r in rows:
            k = r[key]
            for i in range(3):
                totals[k][i] += r[2 + i]
            if prefix in unseen_sets:
                unseen.add(k)
    order = sorted(totals, key=lambda k: -totals[k][0] / sum(totals[k]))
    fig, ax = plt.subplots(figsize=(10, height))
    stacked(ax, order, [totals[k] for k in order], unseen)
    ax.text(1.0, 1.0, "unseen in training", transform=ax.transAxes, ha="right", va="bottom",
            color=UNSEEN, fontsize=12, fontweight="bold")
    save(fig, out_dir, name)


def runs_chart(batch, best, out_dir):
    runs = []
    for path in glob.glob(os.path.join(batch, "run-*", "summary.properties")):
        props = dict(line.strip().split("=", 1) for line in open(path) if "=" in line and not line.startswith("#"))
        runs.append((int(props["randomSeed"]), float(props["winRate"]), os.path.basename(os.path.dirname(path))))
    runs.sort()
    mean = sum(w for _, w, _ in runs) / len(runs)
    fig, ax = plt.subplots(figsize=(10, 3.8))
    ax.hlines(mean, 0.4, len(runs) + 0.4, color=MUTED, lw=1.5, ls=(0, (5, 4)))
    ax.text(len(runs) + 0.55, mean, f"mean {mean:.0%}", va="center", color=MUTED, fontsize=12)
    for seed, w, name in runs:
        is_best = name == best
        ax.vlines(seed, 0.5, w, color=WIN if is_best else GRID, lw=2)
        ax.scatter(seed, w, s=110 if is_best else 70, color=WIN if is_best else INK, zorder=3,
                   edgecolor="white", linewidth=2)
        if is_best:
            ax.annotate(f"chosen bot\n{w:.0%}", (seed, w), xytext=(0, 14), textcoords="offset points",
                        ha="center", color=WIN, fontsize=12, fontweight="bold")
    ax.set_xticks([s for s, _, _ in runs], [f"Run {s}" for s, _, _ in runs])
    ax.set_ylim(0.5, 0.86)
    ax.set_xlim(0.4, len(runs) + 1.6)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    ax.set_ylabel("Training win rate")
    ax.grid(axis="y", color=GRID)
    ax.set_axisbelow(True)
    ax.tick_params(length=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    save(fig, out_dir, "results_runs")


def winrate_time_chart(batch, out_dir):
    """Best bot's training win rate over training: every run in grey, the mean in ink."""
    import json
    runs = {}
    for path in glob.glob(os.path.join(batch, "run-*", "metrics.jsonl")):
        by_gen = {}
        for line in open(path):  # a resumed run repeats generations; keep the last entry for each
            row = json.loads(line)
            by_gen[row["generation"]] = row["winRate"]
        runs[os.path.basename(os.path.dirname(path))] = by_gen
    gens = sorted(set.intersection(*(set(r) for r in runs.values())))
    mean = [sum(r[g] for r in runs.values()) / len(runs) for g in gens]

    fig, ax = plt.subplots(figsize=(10, 4.6))
    for name, r in runs.items():
        ax.plot(gens, [r[g] for g in gens], color=WIN, alpha=0.22, lw=1.1)
    low = [min(r[g] for r in runs.values()) for g in gens]
    high = [max(r[g] for r in runs.values()) for g in gens]
    ax.fill_between(gens, low, high, color=WIN, alpha=0.08, lw=0)
    ax.plot(gens, mean, color=AVERAGE, lw=3, solid_capstyle="round")
    end = gens[-1]
    ax.text(end + 3, mean[-1], "Average", va="center", color=AVERAGE, fontsize=13, fontweight="bold")
    ax.set_xlim(gens[0], end)
    ax.set_ylim(0, 1)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    ax.set_xticks([])
    ax.set_xlabel("Training →")
    ax.set_ylabel("Win rate of best bot")
    ax.grid(axis="y", color=GRID)
    ax.set_axisbelow(True)
    ax.tick_params(length=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    fig.subplots_adjust(right=0.86)
    save(fig, out_dir, "results_winrate_time")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_dir", nargs="?", default="figures")
    ap.add_argument("--results", default="results")
    ap.add_argument("--batch", default="../runs/sbgp-16h")
    ap.add_argument("--best", default="run-7")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    style()
    data = load(args.results)
    sets_chart(data, args.out_dir)
    grouped_chart(data, 1, {"B", "D"}, args.out_dir, "results_opponents", 6.2)
    grouped_chart(data, 0, {"C", "D"}, args.out_dir, "results_maps", 6.2)
    runs_chart(args.batch, args.best, args.out_dir)
    winrate_time_chart(args.batch, args.out_dir)


if __name__ == "__main__":
    main()
