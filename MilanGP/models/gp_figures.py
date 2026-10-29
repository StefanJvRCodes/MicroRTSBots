#!/usr/bin/env python3
"""Poster figures that explain the GP: how a tree decides, crossover and mutation.

Usage:
    python3 gp_figures.py [out_dir] [--format png|pdf|svg|all]

The trees are made-up demo trees built from real node types, kept small so they read on a poster.
The operators mirror GPTreeOps: crossover swaps a subtree for one of the same type (condition for
condition, action for action); mutation regrows a subtree or nudges one number.
"""

import argparse
import os

from visualize_gp_tree import HIGHLIGHT_A, HIGHLIGHT_B, MARGIN, TEXT_MUTED, Tree, open_surface, set_font

DEMO = """(If (EnemyInRange 0.3)
              (If (StrongerThanNearestEnemy) (AttackNearestEnemy) (MoveToOwnBase))
              (If (And (IsMilitary) (GameTimeAtLeast 300)) (AttackEnemyBase) (HarvestResources)))"""

PARENT_A = "(If (EnemyInRange 0.3) (AttackNearestEnemy) (If (CanHarvest) (HarvestResources) (Train Worker)))"
PARENT_B = "(If (And (OwnHasBarracks) (ResourcesAtLeast 0.5)) (Train Light) (BuildBarracks))"
CHILD = ("(If (EnemyInRange 0.3) (AttackNearestEnemy)"
         " (If (And (OwnHasBarracks) (ResourcesAtLeast 0.5)) (HarvestResources) (Train Worker)))")
MUTATED = ("(If (EnemyInRange 0.3) (AttackNearestEnemy)"
           " (If (CanHarvest) (HarvestResources) (If (HPBelow 0.3) (MoveToOwnBase) (Train Ranged))))")
TWEAKED = "(If (EnemyInRange 0.35) (AttackNearestEnemy) (If (CanHarvest) (HarvestResources) (Train Worker)))"

CONNECTOR_W = 140     # horizontal space for a "+" or arrow between panels
ROW_GAP = 70
AREA_LEVELS = 2       # rows in the demo global area (globalAreaDepth is 4 in real runs)


def tree(text, highlight=None, colour=HIGHLIGHT_A, trace=None):
    t = Tree(text)
    if highlight is not None:
        t.highlight(highlight, colour)
    if trace:
        t.trace(trace, colour)
    return t


def draw_connector(ctx, kind, x, y):
    ctx.set_source_rgb(*TEXT_MUTED)
    if kind == "plus":
        set_font(ctx, 44, bold=True)
        ext = ctx.text_extents("+")
        ctx.move_to(x + (CONNECTOR_W - ext.x_advance) / 2, y + 15)
        ctx.show_text("+")
        return
    if kind == "cycle":  # there and back again
        draw_arrow(ctx, x + 34, x + CONNECTOR_W - 34, y - 14)
        draw_arrow(ctx, x + CONNECTOR_W - 34, x + 34, y + 14)
    else:
        draw_arrow(ctx, x + 16, x + CONNECTOR_W - 16, y)


def draw_arrow(ctx, x0, x1, y):
    d = 1 if x1 > x0 else -1
    ctx.set_line_width(3)
    ctx.move_to(x0, y)
    ctx.line_to(x1 - 4 * d, y)
    ctx.stroke()
    ctx.move_to(x1, y)
    ctx.line_to(x1 - 14 * d, y - 9)
    ctx.line_to(x1 - 14 * d, y + 9)
    ctx.close_path()
    ctx.fill()


def explore_tree():
    """Explore: the global area (top AREA_LEVELS rows) is what changes; the rest waits."""
    t = Tree(DEMO)
    t.band(0, AREA_LEVELS - 1, HIGHLIGHT_A)
    t.mark_levels(0, AREA_LEVELS - 1, HIGHLIGHT_A)
    t.fade_levels(AREA_LEVELS, 99)
    return t


def exploit_tree():
    """Exploit: the global area is locked and only the levels below it change."""
    t = Tree(DEMO)
    t.band(0, AREA_LEVELS - 1, TEXT_MUTED, dashed=True)
    t.band(AREA_LEVELS, 99, HIGHLIGHT_B)
    t.mark_levels(AREA_LEVELS, 99, HIGHLIGHT_B)
    return t


def figure(path, rows, scale, row_offset=20):
    """rows: [[Tree, connector, Tree, ...], ...]; connector is "plus" or "arrow"."""
    def row_size(items):
        trees = items[::2]
        return sum(t.width for t in trees) + CONNECTOR_W * (len(items) // 2), max(t.height for t in trees)

    sizes = [row_size(items) for items in rows]
    width = int(max(w for w, _ in sizes) + 2 * MARGIN)
    height = int(sum(h for _, h in sizes) + ROW_GAP * (len(rows) - 1) + 2 * MARGIN)
    _, ctx, finish = open_surface(path, width, height, scale)

    y = MARGIN
    for items, (row_w, row_h) in zip(rows, sizes):
        x = MARGIN + (width - 2 * MARGIN - row_w) / 2
        for i, item in enumerate(items):
            if i % 2:  # connector, level with the root boxes
                draw_connector(ctx, item, x, y + row_offset)
                x += CONNECTOR_W
                continue
            ctx.save()
            ctx.translate(x, y)
            item.draw(ctx)
            ctx.restore()
            x += item.width
        y += row_h + ROW_GAP
    finish()
    print(f"Wrote {path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_dir", nargs="?", default="figures")
    ap.add_argument("--format", default="all", choices=["png", "pdf", "svg", "all"])
    ap.add_argument("--scale", type=float, default=3)
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    exts = ["png", "pdf"] if args.format == "all" else [args.format]

    for ext in exts:
        out = lambda name: os.path.join(args.out_dir, f"{name}.{ext}")

        figure(out("decision"), [[tree(DEMO, trace=[
            ("", None), ("0", "false"), ("2", None), ("2.0", "false"), ("2.0.0", "false"), ("2.2", "chosen")])]],
            args.scale)
        figure(out("crossover"), [[tree(PARENT_A, "2.0"), "plus", tree(PARENT_B, "0", HIGHLIGHT_B), "arrow",
                                   tree(CHILD, "2.0", HIGHLIGHT_B)]], args.scale)
        figure(out("mutation_subtree"), [[tree(PARENT_A, "2.2"), "arrow", tree(MUTATED, "2.2", HIGHLIGHT_B)]],
               args.scale)
        figure(out("structure_phases"), [[explore_tree(), "cycle", exploit_tree()]], args.scale, row_offset=70)
        figure(out("mutation_number"), [[tree(PARENT_A, "0"), "arrow", tree(TWEAKED, "0", HIGHLIGHT_B)]],
               args.scale)

if __name__ == "__main__":
    main()
