#!/usr/bin/env python3
"""Poster figures for turning a MODI tree's output vector into one decision.

Usage:
    python3 modi_adapter.py [out_dir]

modi_no_adapter: the vector goes straight into max, and the largest slot's output is chosen.
modi_adapter:    the vector is the input layer of a small neural network (the adapter), whose
                 strongest output is chosen.
The vector matches modi_tree.py (slots 0, 0, 1, 3).
"""

import os
import sys

from modi_tree import arrow_head, centred
from visualize_gp_tree import (CATEGORIES, EDGE_COLOR, HIGHLIGHT_B, TEXT_MUTED, TEXT_PRIMARY, open_surface,
                               rounded_rect, washed)

import math

W, H = 760, 420
VECTOR = ["0", "0", "1", "3"]
SLOT = 64
VEC_X = 60
VEC_TOP = H / 2 - SLOT * len(VECTOR) / 2
OUT_X, OUT_R = 660, 26
SLATE = CATEGORIES["control"][1]


def slot_y(i):
    return VEC_TOP + i * SLOT + SLOT / 2


def draw_vector(ctx, winner=None):
    for i, value in enumerate(VECTOR):
        y = VEC_TOP + i * SLOT
        rounded_rect(ctx, VEC_X, y, SLOT, SLOT, 6)
        ctx.set_source_rgba(*HIGHLIGHT_B, 0.18 if value != "0" else 0.0)
        ctx.fill_preserve()
        ctx.set_source_rgb(*(HIGHLIGHT_B if i == winner else SLATE))
        ctx.set_line_width(4 if i == winner else 2.5)
        ctx.stroke()
        centred(ctx, value, VEC_X + SLOT / 2, y + SLOT / 2, 24, TEXT_PRIMARY)


def draw_outputs(ctx, chosen, ys):
    for i, y in enumerate(ys):
        ctx.new_path()
        ctx.arc(OUT_X, y, OUT_R, 0, 2 * math.pi)
        if i == chosen:
            ctx.set_source_rgb(*HIGHLIGHT_B)
            ctx.fill()
        else:
            ctx.set_source_rgb(1, 1, 1)
            ctx.fill_preserve()
            ctx.set_source_rgb(*washed(SLATE, 0.45))
            ctx.set_line_width(2.5)
            ctx.stroke()


def line(ctx, x0, y0, x1, y1, rgb, width):
    ctx.set_source_rgb(*rgb)
    ctx.set_line_width(width)
    ctx.move_to(x0, y0)
    ctx.line_to(x1, y1)
    ctx.stroke()


def no_adapter(path):
    _, ctx, finish = open_surface(path, W, H, 3)
    winner = max(range(len(VECTOR)), key=lambda i: float(VECTOR[i]))
    bx, bw, bh = 300, 130, 70
    by = H / 2 - bh / 2
    for i in range(len(VECTOR)):  # every slot feeds max; the winning one stands out
        lit = i == winner
        line(ctx, VEC_X + SLOT, slot_y(i), bx, H / 2 + (i - 1.5) * 12,
             HIGHLIGHT_B if lit else EDGE_COLOR, 3.2 if lit else 2)
    rounded_rect(ctx, bx, by, bw, bh, 12)
    ctx.set_source_rgb(*SLATE)
    ctx.fill()
    centred(ctx, "max", bx + bw / 2, H / 2, 26, (1, 1, 1))
    ys = [slot_y(i) for i in range(len(VECTOR))]
    ctx.set_source_rgb(*HIGHLIGHT_B)
    line(ctx, bx + bw, H / 2, OUT_X - OUT_R - 6, ys[winner], HIGHLIGHT_B, 3.2)
    dx, dy = OUT_X - OUT_R - (bx + bw), ys[winner] - H / 2
    arrow_head(ctx, OUT_X - OUT_R - 2, ys[winner] + dy / math.hypot(dx, dy) * -4, dx, dy, 14)
    draw_vector(ctx, winner)
    draw_outputs(ctx, winner, ys)
    finish()
    print(f"Wrote {path}")


def adapter(path, chosen=2):
    _, ctx, finish = open_surface(path, W, H, 3)
    hidden_x, hidden_r, n_hidden = 370, 20, 5
    hidden = [H / 2 + (j - (n_hidden - 1) / 2) * 62 for j in range(n_hidden)]
    outs = [slot_y(i) for i in range(len(VECTOR))]

    # the adapter: a dashed box around the network
    rounded_rect(ctx, hidden_x - 60, 30, OUT_X + OUT_R + 30 - (hidden_x - 60), H - 60, 18)
    ctx.set_source_rgba(*CATEGORIES["logic"][1], 0.12)
    ctx.fill_preserve()
    ctx.set_source_rgb(*TEXT_MUTED)
    ctx.set_line_width(2.2)
    ctx.set_dash([10, 7])
    ctx.stroke()
    ctx.set_dash([])

    for i in range(len(VECTOR)):
        for hy in hidden:
            line(ctx, VEC_X + SLOT, slot_y(i), hidden_x - hidden_r, hy, EDGE_COLOR, 1.3)
    for hy in hidden:
        for k, oy in enumerate(outs):
            lit = k == chosen
            line(ctx, hidden_x + hidden_r, hy, OUT_X - OUT_R, oy,
                 HIGHLIGHT_B if lit else EDGE_COLOR, 2.4 if lit else 1.3)
    for hy in hidden:
        ctx.new_path()
        ctx.arc(hidden_x, hy, hidden_r, 0, 2 * math.pi)
        ctx.set_source_rgb(*CATEGORIES["logic"][1])
        ctx.fill()
    draw_vector(ctx)
    draw_outputs(ctx, chosen, outs)
    finish()
    print(f"Wrote {path}")


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "figures"
    os.makedirs(out_dir, exist_ok=True)
    for ext in ("png", "pdf"):
        no_adapter(os.path.join(out_dir, f"modi_no_adapter.{ext}"))
        adapter(os.path.join(out_dir, f"modi_adapter.{ext}"))


if __name__ == "__main__":
    main()
