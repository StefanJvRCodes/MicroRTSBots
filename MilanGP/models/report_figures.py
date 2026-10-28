#!/usr/bin/env python3
"""Report tables and charts for the GP sections (report-gp-sections.md), in the poster figure style.

Usage:
    python3 report_figures.py [out_dir] [--batch1 ../runs/sbgp-16h] [--batch2 ../runs/sbgp-16h-machine2]

Tables:  table_terminals, table_phases, table_params, table_sbgp_choices, table_sets, table_batches, table_heldout, table_best_bot
Charts:  results_runs_both, results_heldout_runs, results_matchups
Each is written as PNG and PDF. Colours, fonts and saving come from results_charts.py.
"""

import argparse
import csv
import glob
import itertools
import json
import os
import statistics as st
import textwrap

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

from results_charts import GRID, INK, LOSS, MUTED, SETS, TIE, UNSEEN, WIN, load, save, style

SECOND = "#e09f3e"   # batch 2 in the 20-seed chart

TERMINALS = [
    ["Unit state", "CanHarvest, CanAttack, IsMilitary, IsUnitType(t), IsCarryingResources, HPBelow(f), "
                   "StrongerThanNearestEnemy"],
    ["Distances", "EnemyInAttackRange, EnemyInSightRange, EnemyInRange(f), EnemyBaseInRange(f), "
                  "NearOwnBase(f), ResourceInRange(f)"],
    ["Counts and economy", "ResourcesAtLeast(f), OwnMilitaryAtLeast(f), EnemyMilitaryAtLeast(f), "
                           "EnemyWorkersAtLeast(f), OwnWorkersAtLeast(n), OwnHasBarracks, EnemyHasBarracks"],
    ["Time and roles", "GameTimeAtLeast(n), WorkerAttackRankAtMost(n), WorkerHarvestRankAtMost(n)"],
    ["Actions", "Idle, HarvestResources, AttackNearestEnemy, AttackWeakestEnemy, AttackEnemyBase, Train(t), "
                "TrainOrSave(t), BuildBase, BuildBarracks, MoveToEnemyBase, MoveToOwnBase, MoveToNearestEnemy, "
                "MoveToNearestResource"],
    ["Functions", "If(condition, action, action), And, Or, Not"],
]

PHASES = [
    ["Explore", "At least Sg = 10 generations, and until the best tree is deeper than Dg = 4",
     "Levels 0 to Dg", "It shares Tg = 6 or more nodes with an area already explored"],
    ["Exploit", "Until the best combat score has not improved for Wg = 10 generations",
     "Levels below Dg", "It does not contain the current area"],
]


# ---------- data ----------

def props(path):
    return dict(l.strip().split("=", 1) for l in open(path) if "=" in l and not l.startswith("#"))


def batch_runs(batch):
    """[(seed, summary props, metrics rows)] sorted by seed."""
    out = []
    for d in glob.glob(os.path.join(batch, "run-*")):
        p = props(os.path.join(d, "summary.properties"))
        by_gen = {}
        for line in open(os.path.join(d, "metrics.jsonl")):   # resumed runs repeat generations
            row = json.loads(line)
            by_gen[row["generation"]] = row
        out.append((int(p["randomSeed"]), p, [by_gen[g] for g in sorted(by_gen)]))
    return sorted(out, key=lambda r: r[0])


def mann_whitney_exact(a, b):
    """Two-sided exact Mann-Whitney U test by enumerating every split (fine for 10 vs 10)."""
    def u(x, y):
        return sum((xi > yi) + 0.5 * (xi == yi) for xi in x for yi in y)
    pooled, n = a + b, len(a)
    centre = len(a) * len(b) / 2
    observed = abs(u(a, b) - centre)
    hits = total = 0
    for idx in itertools.combinations(range(len(pooled)), n):
        chosen = set(idx)
        x = [pooled[i] for i in idx]
        y = [pooled[i] for i in range(len(pooled)) if i not in chosen]
        total += 1
        hits += abs(u(x, y) - centre) >= observed - 1e-9
    return u(a, b), hits / total


def pct(x, d=1):
    return f"{x * 100:.{d}f}%"


# ---------- table drawing ----------

def table(out_dir, name, header, rows, widths, wrap, bold_first=True, highlight_last=False, width_in=10):
    """Draw a table in the chart style: bold ink header, grey rules, wrapped cells.

    widths are column fractions of the figure width; wrap is characters per line for each column.
    """
    cells = [[textwrap.fill(str(c), w) if w else str(c) for c, w in zip(r, wrap)] for r in rows]
    heads = [textwrap.fill(h, w) if w else h for h, w in zip(header, wrap)]
    line_h = 0.225  # inches per text line (12 pt at 1.35 line spacing)
    pad = 0.15
    row_lines = [max(c.count("\n") + 1 for c in r) for r in cells]
    head_lines = max(h.count("\n") + 1 for h in heads)
    heights = [head_lines * line_h + 2 * pad] + [n * line_h + 2 * pad for n in row_lines]
    total = sum(heights) + 0.1

    fig = plt.figure(figsize=(width_in, total))
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, 1)
    ax.set_ylim(total, 0)
    ax.axis("off")
    xs = [0.01]
    for w in widths[:-1]:
        xs.append(xs[-1] + w)

    y = 0.05
    for i, (row, h) in enumerate(zip([heads] + cells, heights)):
        is_head = i == 0
        is_total = highlight_last and i == len(heights) - 1
        for j, (x, text) in enumerate(zip(xs, row)):
            weight = "bold" if is_head or is_total or (bold_first and j == 0) else "normal"
            colour = INK if (is_head or j == 0 or is_total) else "#33312e"
            ax.text(x, y + pad, text, va="top", ha="left", fontsize=12.5 if is_head else 12,
                    fontweight=weight, color=colour, linespacing=1.35)
        y += h
        ax.hlines(y, 0.01, 0.99, color=INK if is_head else GRID, lw=1.6 if is_head else 1)
    ax.hlines(0.05, 0.01, 0.99, color=INK, lw=1.6)
    for ext in ("png", "pdf"):
        path = os.path.join(out_dir, f"{name}.{ext}")
        fig.savefig(path, dpi=300, facecolor="white")
        print(f"Wrote {path}")
    plt.close(fig)


# ---------- tables ----------

def terminal_tables(out_dir):
    table(out_dir, "table_terminals", ["Kind", "Nodes"], TERMINALS, [0.24, 0.75], [None, 82])
    table(out_dir, "table_phases", ["Phase", "Lasts", "Operators may change", "Offspring rejected when"],
          PHASES, [0.12, 0.33, 0.2, 0.34], [None, 32, 18, 32])


def markdown_table(path, caption):
    """Header and rows of the Markdown table that follows the line starting with `caption`."""
    lines = open(path, encoding="utf-8").read().splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith(caption))
    rows = []
    for line in lines[start + 1:]:
        if not line.startswith("|"):
            if rows:
                break
            continue
        cells = [c.strip().replace("`", "").replace("*", "") for c in line.strip("|").split("|")]
        if not set("".join(cells)) <= set("-: "):
            rows.append(cells)
    return rows[0], rows[1:]


def text_tables(out_dir, sections):
    """Tables whose text lives in the report sections, so the image and the text cannot drift apart."""
    header, rows = markdown_table(sections, "**Table M.2.")
    table(out_dir, "table_params", header, rows, [0.3, 0.69], [None, 70])
    header, rows = markdown_table(sections, "**Table M.4.")
    table(out_dir, "table_sbgp_choices", header, rows, [0.22, 0.36, 0.41], [22, 38, 44], width_in=11)


def sets_table(out_dir, batch1):
    m = props(os.path.join(batch1, "run-1", "manifest.properties"))
    def names(key):
        return ", ".join(p.split("/")[-1].removesuffix(".xml").replace("mayariBot", "Mayari")
                         .replace("Coacai", "CoacAI") for p in m[key].split(","))
    rows = [["Opponents", names("opponents"), names("holdoutOpponents")],
            ["Maps", names("maps"), names("holdoutMaps")]]
    table(out_dir, "table_sets", ["", "Training", "Held out"], rows, [0.13, 0.48, 0.38], [None, 52, 40])


def batches_table(out_dir, b1, b2):
    def wr(b): return [float(p["winRate"]) for _, p, _ in b]
    # Sum of per-generation times, not summary trainingSeconds: a resumed run's summary only counts the
    # time since the resume (batch 1 run 7 was resumed from generation 160).
    def secs(b): return [sum(r["seconds"] for r in rows) for _, _, rows in b]
    def popm(b): return [float(p["populationWinRateMean"]) for _, p, _ in b]
    def gens(b): return [float(p["generations"]) for _, p, _ in b]
    def areas(b): return [max(r.get("areasExplored", 0) for r in rows) for _, _, rows in b]
    both = b1 + b2
    u, p = mann_whitney_exact(wr(b1), wr(b2))

    def ms(v, f=pct): return f"{f(st.mean(v))} ({f(st.stdev(v))})"
    def rng(v): return f"{pct(min(v))} – {pct(max(v))}"
    def mins(x): return f"{x / 60:.1f} min"
    def g(v):
        stopped = [int(x) for x in v if x < max(v)]
        return f"{int(max(v))} in all runs" if not stopped else f"{st.mean(v):.1f} (one run stopped at {stopped[0]})"
    rows = [
        ["Training win rate, mean (SD)", ms(wr(b1)), ms(wr(b2)), ms(wr(both))],
        ["Training win rate, range", rng(wr(b1)), rng(wr(b2)), rng(wr(both))],
        ["Population mean win rate, final generation, mean (SD)", ms(popm(b1)), ms(popm(b2)), "—"],
        ["Generations", g(gens(b1)), g(gens(b2)), "—"],
        ["Training time per run, mean (SD)", ms(secs(b1), mins), ms(secs(b2), mins), "—"],
        ["Total training time", f"{sum(secs(b1)) / 3600:.1f} h", f"{sum(secs(b2)) / 3600:.1f} h",
         f"{sum(secs(both)) / 3600:.1f} h"],
        ["Global areas explored per run, mean", f"{st.mean(areas(b1)):.1f}", f"{st.mean(areas(b2)):.1f}",
         f"{st.mean(areas(both)):.1f}"],
        ["Mann-Whitney U, batch 1 vs batch 2", f"U = {u:g}, p = {p:.3f} (exact, two-sided)", "", ""],
    ]
    table(out_dir, "table_batches", ["", "Batch 1", "Batch 2", "All 20 runs"], rows,
          [0.36, 0.22, 0.22, 0.19], [34, 22, 22, 18])
    print(f"  batch MW: U={u}, p={p:.4f}")


def heldout_table(out_dir, batch1):
    rows = {r["test"]: r for r in csv.DictReader(open(os.path.join(batch1, "poster", "1b_win_rates_long.csv")))}
    labels = [("training", "Training opponents, training maps (training)"),
              ("heldout_bots", "Held-out opponents, training maps"),
              ("heldout_maps", "Training opponents, held-out maps"),
              ("heldout_combined", "Held-out opponents, held-out maps")]
    body = [[label, f"{pct(float(rows[k]['win_mean']))} ({pct(float(rows[k]['win_sd']))})"] for k, label in labels]
    table(out_dir, "table_heldout", ["Test set", "Win rate, mean (SD) over 10 runs"], body, [0.6, 0.39],
          [None, None], width_in=8)


def best_bot_table(out_dir, data):
    rows, totals = [], [0, 0, 0]
    labels = ["Training opponents, training maps", "Held-out opponents, training maps",
              "Training opponents, held-out maps", "Held-out opponents, held-out maps"]
    for (prefix, _), label in zip(SETS, labels):
        w, t, l = (sum(r[i] for r in data[prefix]) for i in (2, 3, 4))
        totals = [a + b for a, b in zip(totals, (w, t, l))]
        rows.append([label, w, t, l, pct(w / (w + t + l))])
    w, t, l = totals
    rows.append(["All games", w, t, l, pct(w / (w + t + l))])
    table(out_dir, "table_best_bot", ["Test set", "Wins", "Draws", "Losses", "Win rate"], rows,
          [0.48, 0.12, 0.12, 0.13, 0.14], [None] * 5, highlight_last=True, width_in=9)


# ---------- charts ----------

def clean(ax):
    ax.tick_params(length=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)


def runs_both_chart(out_dir, b1, b2):
    """Training win rate of all 20 runs, the two machines side by side per seed."""
    fig, ax = plt.subplots(figsize=(10, 4.2))
    for runs, colour, dx, label in ((b1, WIN, -0.17, "Batch 1"), (b2, SECOND, 0.17, "Batch 2")):
        seeds = [s + dx for s, _, _ in runs]
        rates = [float(p["winRate"]) for _, p, _ in runs]
        ax.vlines(seeds, 0.5, rates, color=colour, alpha=0.35, lw=2)
        ax.scatter(seeds, rates, s=70, color=colour, zorder=3, edgecolor="white", linewidth=2, label=label)
        mean = st.mean(rates)
        ax.hlines(mean, 0.5, 10.5, color=colour, lw=1.5, ls=(0, (5, 4)))
        ax.text(10.65, mean, f"mean {mean:.0%}", va="center", color=colour, fontsize=12, fontweight="bold")
    ax.set_xticks(range(1, 11), [f"Seed {s}" for s in range(1, 11)])
    ax.set_ylim(0.5, 0.86)
    ax.set_xlim(0.4, 11.9)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    ax.set_ylabel("Training win rate")
    ax.grid(axis="y", color=GRID)
    ax.set_axisbelow(True)
    clean(ax)
    ax.legend(loc="lower left", bbox_to_anchor=(0, 1.0), ncol=2, frameon=False, fontsize=12)
    save(fig, out_dir, "results_runs_both")


def heldout_runs_chart(out_dir, batch1):
    """Mean win rate of the 10 batch-1 bots per test set, with each run's own result as a dot."""
    rows = {r["test"]: r for r in csv.DictReader(open(os.path.join(batch1, "poster", "1b_win_rates_long.csv")))}
    order = [("training", "Seen opponents\nseen maps"), ("heldout_bots", "Unseen opponents\nseen maps"),
             ("heldout_maps", "Seen opponents\nunseen maps"), ("heldout_combined", "Unseen opponents\nunseen maps")]
    fig, ax = plt.subplots(figsize=(10, 4.6))
    ys = list(range(len(order)))[::-1]
    for y, (key, _) in zip(ys, order):
        mean, sd = float(rows[key]["win_mean"]), float(rows[key]["win_sd"])
        colour = WIN if key == "training" else UNSEEN
        ax.barh(y, mean, color=colour, height=0.6, alpha=0.9)
        ax.errorbar(mean, y, xerr=sd, color=INK, capsize=6, lw=1.6, capthick=1.6)
        ax.text(mean + sd + 0.015, y, f"{mean:.0%} ± {sd:.0%}", va="center", color=INK, fontsize=12,
                fontweight="bold")
    ax.set_yticks(ys, [label for _, label in order])
    ax.set_xlim(0, 1)
    ax.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0))
    ax.set_xlabel("Win rate, mean ± SD over 10 runs")
    ax.grid(axis="x", color=GRID)
    ax.set_axisbelow(True)
    clean(ax)
    ax.spines["left"].set_visible(False)
    save(fig, out_dir, "results_heldout_runs")


def matchups_chart(out_dir, data):
    """Best bot's win rate for every (map, opponent) pair: one cell per 20 games."""
    from matplotlib.colors import LinearSegmentedColormap
    cells = {}
    for prefix, rows in data.items():
        for mp, opp, w, t, l in rows:
            cells[(mp, opp)] = w / (w + t + l)
    train_maps = [r[0] for r in data["A"]]
    hold_maps = [r[0] for r in data["C"]]
    maps = list(dict.fromkeys(train_maps)) + list(dict.fromkeys(hold_maps))
    train_opps = list(dict.fromkeys(r[1] for r in data["A"]))
    hold_opps = list(dict.fromkeys(r[1] for r in data["B"]))
    opps = train_opps + hold_opps
    grid = [[cells[(m, o)] for o in opps] for m in maps]

    cmap = LinearSegmentedColormap.from_list("wl", [LOSS, "#f4f1ec", WIN])
    fig, ax = plt.subplots(figsize=(11, 7.4))
    im = ax.imshow(grid, cmap=cmap, vmin=0, vmax=1, aspect="auto")
    for i, row in enumerate(grid):
        for j, v in enumerate(row):
            ax.text(j, i, f"{v:.0%}", ha="center", va="center", fontsize=10.5,
                    color="white" if v > 0.8 or v < 0.2 else INK, fontweight="bold")
    ax.set_xticks(range(len(opps)), [o.replace("mayariBot", "Mayari").replace("Coacai", "CoacAI") for o in opps],
                  rotation=40, ha="right")
    ax.set_yticks(range(len(maps)), maps)
    for tick in ax.get_xticklabels()[len(train_opps):]:
        tick.set_color(UNSEEN)
        tick.set_fontweight("bold")
    for tick in ax.get_yticklabels()[len(set(train_maps)):]:
        tick.set_color(UNSEEN)
        tick.set_fontweight("bold")
    ax.axvline(len(train_opps) - 0.5, color="white", lw=4)
    ax.axhline(len(set(train_maps)) - 0.5, color="white", lw=4)
    ax.tick_params(length=0)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.text(1.0, 1.01, "unseen in training", transform=ax.transAxes, ha="right", va="bottom",
            color=UNSEEN, fontsize=12, fontweight="bold")
    bar = fig.colorbar(im, ax=ax, fraction=0.03, pad=0.02, format=matplotlib.ticker.PercentFormatter(1.0))
    bar.outline.set_visible(False)
    bar.ax.tick_params(length=0, colors=MUTED)
    bar.set_label("Win rate (20 games)", color=MUTED)
    save(fig, out_dir, "results_matchups")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_dir", nargs="?", default="figures")
    ap.add_argument("--results", default="results")
    ap.add_argument("--batch1", default="../runs/sbgp-16h")
    ap.add_argument("--batch2", default="../runs/sbgp-16h-machine2")
    ap.add_argument("--sections", default="../report-gp-sections.md")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    style()
    b1, b2 = batch_runs(args.batch1), batch_runs(args.batch2)
    data = load(args.results)

    terminal_tables(args.out_dir)
    text_tables(args.out_dir, args.sections)
    sets_table(args.out_dir, args.batch1)
    batches_table(args.out_dir, b1, b2)
    heldout_table(args.out_dir, args.batch1)
    best_bot_table(args.out_dir, data)

    runs_both_chart(args.out_dir, b1, b2)
    heldout_runs_chart(args.out_dir, args.batch1)
    matchups_chart(args.out_dir, data)


if __name__ == "__main__":
    main()
