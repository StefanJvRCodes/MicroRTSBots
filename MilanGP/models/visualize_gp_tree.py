#!/usr/bin/env python3
"""Render a Lisp-style GP individual (e.g. gp_bot_best.txt) as a PNG tree.

Usage:
    python3 visualize_gp_tree.py [input_file] [output_file]

Defaults to gp_bot_best.txt -> gp_bot_best.png
"""

import re
import sys

import cairo

# --- Colour palette (validated categorical set + reserved status red) ---
COLOR_CONTROL = (0x2a / 255, 0x78 / 255, 0xd6 / 255)   # blue   - control flow (If)
COLOR_LOGIC = (0xeb / 255, 0x68 / 255, 0x34 / 255)     # orange - boolean logic (And/Or/Not)
COLOR_CONDITION = (0x1b / 255, 0xaf / 255, 0x7a / 255)  # aqua   - sensors/conditions (leaves)
COLOR_ACTION = (0xd0 / 255, 0x3b / 255, 0x3b / 255)     # red    - actions (leaves)
COLOR_OTHER = (0x89 / 255, 0x87 / 255, 0x81 / 255)      # muted gray - anything unrecognised

SURFACE_BG = (0xfc / 255, 0xfc / 255, 0xfb / 255)
TEXT_PRIMARY = (0x0b / 255, 0x0b / 255, 0x0b / 255)
TEXT_ON_COLOR = (1, 1, 1)
EDGE_COLOR = (0xc3 / 255, 0xc2 / 255, 0xb7 / 255)

CONTROL_FUNCS = {"If"}
LOGIC_FUNCS = {"And", "Or", "Not"}
CONDITION_FUNCS = {
    "True", "False", "CanAttack", "CanHarvest",
    "ResourcesAtLeast", "HPBelow", "NearOwnBase", "EnemyInRange",
}
ACTION_FUNCS = {
    "AttackNearestEnemy", "TrainWorker", "BuildBarracks", "MoveToEnemyBase",
}

FONT_FAMILY = "sans-serif"
FONT_SIZE = 15
BOX_PAD_X = 14
BOX_PAD_Y = 10
BOX_RADIUS = 8
H_GAP = 18
V_GAP = 55
MARGIN = 30
TITLE_HEIGHT = 50


class Node:
    def __init__(self, label):
        self.label = label
        self.children = []
        self.x = 0.0
        self.y = 0.0
        self.w = 0.0
        self.h = 0.0


def tokenize(text):
    return text.replace("(", " ( ").replace(")", " ) ").split()


def parse_tokens(tokens):
    if not tokens:
        raise ValueError("Unexpected end of expression")
    tok = tokens.pop(0)
    if tok == "(":
        items = []
        while tokens[0] != ")":
            items.append(parse_tokens(tokens))
        tokens.pop(0)  # discard ')'
        return items
    if tok == ")":
        raise ValueError("Unexpected ')'")
    return tok


def is_number(tok):
    return bool(re.match(r"^-?\d+(\.\d+)?$", tok))


def build_tree(expr):
    """Convert a parsed nested-list expression into a Node tree.

    Numeric-only trailing arguments (e.g. ``(ResourcesAtLeast 15)``) are
    folded into the parent's label as ``ResourcesAtLeast(15)`` so terminal
    checks render as a single compact leaf instead of an extra hop.
    """
    if isinstance(expr, str):
        return Node(expr)

    head, *rest = expr
    if rest and all(isinstance(r, str) and is_number(r) for r in rest):
        label = f"{head}({', '.join(rest)})"
        return Node(label)

    node = Node(head)
    node.children = [build_tree(child) for child in rest]
    return node


def classify(label):
    name = label.split("(")[0]
    if name in CONTROL_FUNCS:
        return COLOR_CONTROL
    if name in LOGIC_FUNCS:
        return COLOR_LOGIC
    if name in CONDITION_FUNCS:
        return COLOR_CONDITION
    if name in ACTION_FUNCS:
        return COLOR_ACTION
    return COLOR_OTHER


def measure(ctx, node, depth=0):
    ctx.select_font_face(FONT_FAMILY, cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(FONT_SIZE)
    extents = ctx.text_extents(node.label)
    node.w = extents.width + 2 * BOX_PAD_X
    node.h = FONT_SIZE + 2 * BOX_PAD_Y
    node.depth = depth
    max_depth = depth
    for child in node.children:
        max_depth = max(max_depth, measure(ctx, child, depth + 1))
    return max_depth


def layout(node, cursor):
    """Assign x/y centers. `cursor` is a single-element list used as a mutable int."""
    if not node.children:
        node.x = cursor[0] + node.w / 2
        cursor[0] += node.w + H_GAP
    else:
        for child in node.children:
            layout(child, cursor)
        first, last = node.children[0], node.children[-1]
        node.x = (first.x + last.x) / 2
    node.y = TITLE_HEIGHT + MARGIN + node.depth * V_GAP + node.h / 2
    return cursor[0]


def draw_rounded_rect(ctx, x, y, w, h, r):
    ctx.new_sub_path()
    ctx.arc(x + w - r, y + r, r, -90 * (3.14159265 / 180), 0)
    ctx.arc(x + w - r, y + h - r, r, 0, 90 * (3.14159265 / 180))
    ctx.arc(x + r, y + h - r, r, 90 * (3.14159265 / 180), 180 * (3.14159265 / 180))
    ctx.arc(x + r, y + r, r, 180 * (3.14159265 / 180), 270 * (3.14159265 / 180))
    ctx.close_path()


def draw_edges(ctx, node):
    for child in node.children:
        ctx.set_source_rgb(*EDGE_COLOR)
        ctx.set_line_width(1.6)
        py = node.y + node.h / 2
        cy = child.y - child.h / 2
        mid_y = (py + cy) / 2
        ctx.move_to(node.x, py)
        ctx.line_to(node.x, mid_y)
        ctx.line_to(child.x, mid_y)
        ctx.line_to(child.x, cy)
        ctx.stroke()
        draw_edges(ctx, child)


def draw_nodes(ctx, node):
    color = classify(node.label)
    x, y, w, h = node.x - node.w / 2, node.y - node.h / 2, node.w, node.h
    draw_rounded_rect(ctx, x, y, w, h, BOX_RADIUS)
    ctx.set_source_rgb(*color)
    ctx.fill_preserve()
    ctx.set_source_rgba(0, 0, 0, 0.12)
    ctx.set_line_width(1)
    ctx.stroke()

    ctx.select_font_face(FONT_FAMILY, cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(FONT_SIZE)
    extents = ctx.text_extents(node.label)
    ctx.set_source_rgb(*TEXT_ON_COLOR)
    ctx.move_to(node.x - extents.width / 2 - extents.x_bearing, node.y - extents.height / 2 - extents.y_bearing)
    ctx.show_text(node.label)

    for child in node.children:
        draw_nodes(ctx, child)


def draw_legend(ctx, x, y):
    entries = [
        ("If / control flow", COLOR_CONTROL),
        ("And / Or / Not", COLOR_LOGIC),
        ("Sensor condition", COLOR_CONDITION),
        ("Action", COLOR_ACTION),
    ]
    ctx.select_font_face(FONT_FAMILY, cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_NORMAL)
    ctx.set_font_size(13)
    swatch = 14
    for i, (text, color) in enumerate(entries):
        ex = x
        ey = y + i * (swatch + 8)
        draw_rounded_rect(ctx, ex, ey, swatch, swatch, 3)
        ctx.set_source_rgb(*color)
        ctx.fill()
        ctx.set_source_rgb(*TEXT_PRIMARY)
        ctx.move_to(ex + swatch + 8, ey + swatch - 3)
        ctx.show_text(text)


def render(tree_text, output_path, title):
    expr = parse_tokens(tokenize(tree_text))
    root = build_tree(expr)

    # measurement pass on a throwaway surface to get real text extents
    probe = cairo.ImageSurface(cairo.FORMAT_ARGB32, 10, 10)
    probe_ctx = cairo.Context(probe)
    max_depth = measure(probe_ctx, root)

    cursor = [MARGIN]
    total_leaf_width = layout(root, cursor)
    width = int(total_leaf_width + MARGIN)
    height = int(TITLE_HEIGHT + MARGIN * 2 + (max_depth + 1) * V_GAP + 140)  # + legend space

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, max(width, 500), height)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*SURFACE_BG)
    ctx.paint()

    ctx.select_font_face(FONT_FAMILY, cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(20)
    ctx.set_source_rgb(*TEXT_PRIMARY)
    ctx.move_to(MARGIN, 32)
    ctx.show_text(title)

    draw_edges(ctx, root)
    draw_nodes(ctx, root)
    draw_legend(ctx, MARGIN, height - 110)

    surface.write_to_png(output_path)


def main():
    input_path = sys.argv[1] if len(sys.argv) > 1 else "baseline.txt"
    output_path = sys.argv[2] if len(sys.argv) > 2 else input_path.rsplit(".", 1)[0] + ".png"

    with open(input_path) as f:
        tree_text = f.read().strip()

    title = f"GP tree — {input_path}"
    render(tree_text, output_path, title)
    print(f"Wrote {output_path}")


if __name__ == "__main__":
    main()
