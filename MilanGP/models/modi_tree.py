#!/usr/bin/env python3
"""Poster figure of a MODI tree: one GP tree that fills an output vector.

Usage:
    python3 modi_tree.py [output.png|.pdf|.svg]

Each modi node writes its result to its slot and passes the same value up: modi 1 computes 3 - 2 = 1;
the root modi computes 1 + ABS(-2) = 3.
"""

import math
import sys

import cairo

from visualize_gp_tree import (CATEGORIES, EDGE_COLOR, HIGHLIGHT_A, HIGHLIGHT_B, TEXT_MUTED, TEXT_PRIMARY,
                               open_surface, rounded_rect, set_font)

W, H = 940, 580
SLOT = 64
SLOTS_X, SLOTS_Y = 40, 40
SLOT_VALUES = [None, None, "1", "3"]

# name: (x, y, radius, kind, label lines); kind "modi" is drawn as a split circle (index / op)
NODES = {
    "root": (540, 190, 62, "modi", ("4", "modi", "L + R")),
    "m1": (340, 350, 62, "modi", ("3", "modi", "L − R")),
    "abs": (740, 350, 48, "func", ("ABS",)),
    "three": (220, 505, 42, "leaf", ("3",)),
    "two": (450, 505, 42, "leaf", ("2",)),
    "neg": (850, 505, 42, "leaf", ("−2",)),
}
# (parent, child, value passed up)
EDGES = [("root", "m1", "1"), ("root", "abs", "2"), ("m1", "three", "3"), ("m1", "two", "2"),
         ("abs", "neg", "−2")]
# (modi node, output slot it writes to)
WRITES = [("m1", 2), ("root", 3)]

FILL = {"modi": HIGHLIGHT_A, "func": CATEGORIES["logic"][1], "leaf": CATEGORIES["condition"][1]}
TEXT = {"modi": (1, 1, 1), "func": CATEGORIES["logic"][2], "leaf": (1, 1, 1)}


def centred(ctx, text, x, y, size, rgb, bold=True):
    set_font(ctx, size, bold)
    ext = ctx.text_extents(text)
    ctx.set_source_rgb(*rgb)
    ctx.move_to(x - ext.x_advance / 2, y + size * 0.35)
    ctx.show_text(text)


def arrow_head(ctx, x, y, dx, dy, size=11):
    n = math.hypot(dx, dy)
    ux, uy = dx / n, dy / n
    ctx.move_to(x, y)
    ctx.line_to(x - size * ux + size * 0.6 * uy, y - size * uy - size * 0.6 * ux)
    ctx.line_to(x - size * ux - size * 0.6 * uy, y - size * uy + size * 0.6 * ux)
    ctx.close_path()
    ctx.fill()


def draw_slots(ctx):
    for i, value in enumerate(SLOT_VALUES):
        x = SLOTS_X + i * SLOT
        rounded_rect(ctx, x, SLOTS_Y, SLOT, SLOT, 6)
        ctx.set_source_rgba(*HIGHLIGHT_B, 0.18 if value else 0.0)
        ctx.fill_preserve()
        ctx.set_source_rgb(*CATEGORIES["control"][1])
        ctx.set_line_width(2.5)
        ctx.stroke()
        if value:
            centred(ctx, value, x + SLOT / 2, SLOTS_Y + SLOT / 2, 24, TEXT_PRIMARY)
        centred(ctx, str(i + 1), x + SLOT / 2, SLOTS_Y - 16, 15, TEXT_MUTED)  # slot number


def draw_writes(ctx):
    ctx.set_line_width(2.5)
    for name, slot in WRITES:
        nx, ny, r, *_ = NODES[name]
        sx = SLOTS_X + slot * SLOT + SLOT / 2
        top = SLOTS_Y + SLOT
        ctx.set_source_rgb(*HIGHLIGHT_A)
        ctx.set_dash([8, 6])
        ctx.move_to(nx - r, ny)
        ctx.line_to(sx, ny)
        ctx.line_to(sx, top + 12)
        ctx.stroke()
        ctx.set_dash([])
        arrow_head(ctx, sx, top + 1, 0, -1, 13)


def draw_edges(ctx):
    for parent, child, value in EDGES:
        px, py, pr, *_ = NODES[parent]
        cx, cy, cr, *_ = NODES[child]
        d = math.hypot(cx - px, cy - py)
        ux, uy = (cx - px) / d, (cy - py) / d
        ctx.set_source_rgb(*EDGE_COLOR)
        ctx.set_line_width(2.2)
        ctx.move_to(px + ux * pr, py + uy * pr)
        ctx.line_to(cx - ux * cr, cy - uy * cr)
        ctx.stroke()
        # value badge on the edge, with an arrow pointing up the edge (towards the parent)
        mx, my = (px + cx) / 2, (py + cy) / 2
        set_font(ctx, 17, bold=True)
        w = ctx.text_extents(value).x_advance + 34
        rounded_rect(ctx, mx - w / 2, my - 15, w, 30, 15)
        ctx.set_source_rgb(*HIGHLIGHT_B)
        ctx.fill()
        ctx.set_source_rgb(1, 1, 1)
        ctx.set_line_width(2)
        ax = mx - w / 2 + 13
        ctx.move_to(ax, my + 7)
        ctx.line_to(ax, my - 3)
        ctx.stroke()
        arrow_head(ctx, ax, my - 8, 0, -1, 7)
        ctx.move_to(ax + 9, my + 17 * 0.35)
        ctx.show_text(value)


def draw_nodes(ctx):
    for x, y, r, kind, lines in NODES.values():
        ctx.new_path()
        ctx.arc(x, y, r, 0, 2 * math.pi)
        ctx.set_source_rgb(*FILL[kind])
        ctx.fill()
        if kind == "modi":
            index, name, op = lines
            split = y - r * 0.38
            half = math.sqrt(r * r - (split - y) ** 2)
            ctx.set_source_rgba(1, 1, 1, 0.7)
            ctx.set_line_width(2)
            ctx.move_to(x - half, split)
            ctx.line_to(x + half, split)
            ctx.stroke()
            centred(ctx, index, x, y - r * 0.66, 19, TEXT[kind])
            centred(ctx, name, x, y + r * 0.02, 18, TEXT[kind], bold=False)
            centred(ctx, op, x, y + r * 0.4, 20, TEXT[kind])
        else:
            centred(ctx, lines[0], x, y, 22, TEXT[kind])


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "figures/modi_tree.png"
    _, ctx, finish = open_surface(out, W, H, 3)
    draw_edges(ctx)
    draw_writes(ctx)
    draw_slots(ctx)
    draw_nodes(ctx)
    finish()
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
