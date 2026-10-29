#!/usr/bin/env python3
"""Render a Lisp-style GP individual as a compact, poster-friendly tree.

Usage:
    python3 visualize_gp_tree.py input.txt [output.png|.pdf|.svg] [--max-depth N] [--highlight PATH]...

    --max-depth N     collapse everything below depth N into a "..." node (smaller poster tree)
    --highlight PATH  outline a subtree; PATH is child indices from the root, e.g. 1.0 = root's
                      second child, then its first child ("" = whole tree). Repeatable.
    --scale S         pixel scale for PNG output (default 3, print quality)
    --title T         title text ("" for none); defaults to the input file name
    --bare            tree only: no title, no legend
PDF and SVG output are vector, so they stay sharp at any poster size.
gp_figures.py builds the crossover / mutation / decision figures on top of this.
"""

import argparse
import math
import re

import cairo


def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))


# --- Palette: (legend text, box colour, text colour) per category ---
CATEGORIES = {
    "control": ("If (control flow)", hex_rgb("#264653"), (1, 1, 1)),
    "logic": ("And / Or / Not", hex_rgb("#e9c46a"), hex_rgb("#3a2e0b")),
    "condition": ("Condition", hex_rgb("#21867a"), (1, 1, 1)),
    "action": ("Action", hex_rgb("#c8553d"), (1, 1, 1)),
    "collapsed": ("Collapsed subtree", hex_rgb("#d9d6cf"), hex_rgb("#4a4843")),
}
HIGHLIGHT_A = hex_rgb("#7b2cbf")   # violet: the selected / removed part
HIGHLIGHT_B = hex_rgb("#0096c7")   # blue: the donated / new part
BACKGROUND = (1, 1, 1)
TEXT_PRIMARY = hex_rgb("#1b1b1b")
TEXT_MUTED = hex_rgb("#5c5a55")
EDGE_COLOR = hex_rgb("#9a9890")

LOGIC_FUNCS = {"And", "Or", "Not"}
ACTION_FUNCS = {
    "AttackEnemyBase", "AttackNearestEnemy", "AttackWeakestEnemy", "BuildBarracks", "BuildBase",
    "HarvestResources", "Idle", "MoveToEnemyBase", "MoveToNearestEnemy", "MoveToNearestResource",
    "MoveToOwnBase", "Train", "TrainOrSave",
}

FONT_FAMILY = "sans-serif"
FONT_SIZE = 15
LINE_HEIGHT = 19
WRAP_AT = 13          # labels longer than this split onto two lines at a CamelCase boundary
BOX_PAD_X = 12
BOX_PAD_Y = 9
BOX_RADIUS = 9
H_GAP = 14            # minimum gap between neighbouring boxes on the same row
ROW_GAP = 46          # vertical space between rows (where the edges run)
MARGIN = 40
TITLE_HEIGHT = 56
LEGEND_HEIGHT = 34


class Node:
    def __init__(self, label, kind):
        self.label, self.kind = label, kind
        self.children = []
        self.lines, self.w, self.h, self.x, self.y = [], 0.0, 0.0, 0.0, 0.0
        self.mark = None      # highlight colour, or None
        self.badge = None     # small tag drawn above the box, e.g. "true"
        self.faded = False    # drawn washed out, for "this part is not in play"
        self.depth = 0


class Tree:
    """A measured, laid-out tree whose top-left corner sits at (0, 0)."""

    def __init__(self, text, max_depth=None):
        self.root = build_tree(parse(text), max_depth=max_depth)
        probe = cairo.Context(cairo.ImageSurface(cairo.FORMAT_ARGB32, 1, 1))
        set_font(probe, FONT_SIZE, bold=True)
        measure(probe, self.root)
        contour = layout(self.root)
        row_h = max(n.h for n in walk(self.root))
        left = min(lo for lo, _ in contour.values())
        self.width = max(hi for _, hi in contour.values()) - left
        self.height = len(contour) * (row_h + ROW_GAP) - ROW_GAP
        self.row_h = row_h
        self.bands = []
        place(self.root, -left, 0, row_h)

    def node(self, path):
        node = self.root
        for i in (int(p) for p in str(path).split(".") if p != ""):
            node = node.children[i]
        return node

    def highlight(self, path, colour=HIGHLIGHT_A):
        for n in walk(self.node(path)):
            n.mark = colour

    def trace(self, steps, colour=HIGHLIGHT_A):
        """Mark single nodes along a decision path: steps = [(path, badge or None), ...]."""
        for path, badge in steps:
            n = self.node(path)
            n.mark, n.badge = colour, badge

    def mark_levels(self, lo, hi, colour):
        for n in walk(self.root):
            if lo <= n.depth <= hi:
                n.mark = colour

    def fade_levels(self, lo, hi):
        for n in walk(self.root):
            if lo <= n.depth <= hi:
                n.faded = True

    def band(self, lo, hi, colour, dashed=False):
        """A shaded box behind rows lo..hi, e.g. the global area of structure-based GP."""
        self.bands.append((lo, hi, colour, dashed))

    def kinds(self):
        return {n.kind for n in walk(self.root)}

    def draw(self, ctx):
        step = self.row_h + ROW_GAP
        last_row = max(n.depth for n in walk(self.root))
        for lo, hi, colour, dashed in self.bands:
            hi, pad = min(hi, last_row), 14
            rounded_rect(ctx, -pad, lo * step - pad, self.width + 2 * pad, (hi - lo + 1) * step - ROW_GAP + 2 * pad, 16)
            ctx.set_source_rgba(*colour, 0.10)
            ctx.fill_preserve()
            ctx.set_source_rgb(*colour)
            ctx.set_line_width(2.5)
            ctx.set_dash([10, 7] if dashed else [])
            ctx.stroke()
            ctx.set_dash([])
        draw(ctx, self.root)


def parse(text):
    tokens = text.replace("(", " ( ").replace(")", " ) ").split()

    def read():
        tok = tokens.pop(0)
        if tok != "(":
            return tok
        items = []
        while tokens[0] != ")":
            items.append(read())
        tokens.pop(0)
        return items

    return read()


def classify(name):
    if name == "If":
        return "control"
    if name in LOGIC_FUNCS:
        return "logic"
    if name in ACTION_FUNCS:
        return "action"
    return "condition"


def count(expr):
    return 1 if isinstance(expr, str) else 1 + sum(count(c) for c in expr[1:] if isinstance(c, list))


def build_tree(expr, depth=0, max_depth=None):
    """Plain arguments (numbers, unit types) fold into the label: (Train Worker) -> Train(Worker)."""
    if isinstance(expr, str):
        return Node(expr, classify(expr))
    head, *rest = expr
    if all(isinstance(r, str) for r in rest):
        return Node(f"{head}({', '.join(rest)})" if rest else head, classify(head))
    if max_depth is not None and depth >= max_depth:
        return Node(f"… {count(expr)} nodes", "collapsed")
    node = Node(head, classify(head))
    node.children = [build_tree(c, depth + 1, max_depth) for c in rest]
    return node


def wrap(label):
    if len(label) <= WRAP_AT:
        return [label]
    name = label.split("(")[0]
    splits = [m.start() for m in re.finditer(r"[A-Z]", name) if m.start() > 0]
    if not splits:
        return [label]
    cut = min(splits, key=lambda i: abs(i - len(label) / 2))
    return [label[:cut], label[cut:]]


def set_font(ctx, size, bold=False):
    weight = cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL
    ctx.select_font_face(FONT_FAMILY, cairo.FONT_SLANT_NORMAL, weight)
    ctx.set_font_size(size)


def measure(ctx, node):
    node.lines = wrap(node.label)
    node.w = max(ctx.text_extents(line).x_advance for line in node.lines) + 2 * BOX_PAD_X
    node.h = len(node.lines) * LINE_HEIGHT + 2 * BOX_PAD_Y
    for child in node.children:
        measure(ctx, child)


def layout(node):
    """Tidy-tree layout: place each subtree as close to its left sibling as their row contours allow.

    Returns the subtree's contour {depth offset: (left, right)} relative to node centre; sets each
    child's `dx` offset from its parent.
    """
    contour = {0: (-node.w / 2, node.w / 2)}
    if not node.children:
        return contour
    merged, offsets = {}, []
    for child in node.children:
        c = layout(child)
        shift = 0.0
        if merged:
            shift = max(merged[d][1] + H_GAP - c[d][0] for d in c if d in merged)
        offsets.append(shift)
        for d, (lo, hi) in c.items():
            lo, hi = lo + shift, hi + shift
            merged[d] = (min(merged[d][0], lo), max(merged[d][1], hi)) if d in merged else (lo, hi)
    centre = (offsets[0] + offsets[-1]) / 2
    for child, off in zip(node.children, offsets):
        child.dx = off - centre
    for d, (lo, hi) in merged.items():
        contour[d + 1] = (lo - centre, hi - centre)
    return contour


def place(node, x, depth, row_h):
    node.x, node.y, node.depth = x, depth * (row_h + ROW_GAP) + row_h / 2, depth
    for child in node.children:
        place(child, x + child.dx, depth + 1, row_h)


def washed(rgb, amount=0.7):
    return tuple(c + (1 - c) * amount for c in rgb)


def walk(node):
    yield node
    for child in node.children:
        yield from walk(child)


def rounded_rect(ctx, x, y, w, h, r):
    ctx.new_sub_path()
    ctx.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    ctx.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    ctx.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    ctx.arc(x + r, y + r, r, math.pi, 1.5 * math.pi)
    ctx.close_path()


def draw(ctx, root):
    for node in walk(root):
        for child in node.children:
            y0, y1 = node.y + node.h / 2, child.y - child.h / 2
            mid = (y0 + y1) / 2
            ctx.set_source_rgb(*(child.mark or (washed(EDGE_COLOR) if child.faded else EDGE_COLOR)))
            ctx.set_line_width(3.2 if child.mark else 1.8)
            ctx.move_to(node.x, y0)
            ctx.curve_to(node.x, mid, child.x, mid, child.x, y1)
            ctx.stroke()
    set_font(ctx, FONT_SIZE, bold=True)
    for node in walk(root):
        x, y = node.x - node.w / 2, node.y - node.h / 2
        if node.mark:  # soft halo + outline
            rounded_rect(ctx, x - 6, y - 6, node.w + 12, node.h + 12, BOX_RADIUS + 5)
            ctx.set_source_rgba(*node.mark, 0.22)
            ctx.fill()
        _, fill, text = CATEGORIES[node.kind]
        if node.faded:
            fill, text = washed(fill), washed(text)
        rounded_rect(ctx, x, y, node.w, node.h, BOX_RADIUS)
        ctx.set_source_rgb(*fill)
        ctx.fill_preserve()
        if node.mark:
            ctx.set_source_rgb(*node.mark)
            ctx.set_line_width(3)
            ctx.stroke()
        ctx.new_path()
        ctx.set_source_rgb(*text)
        top = node.y - len(node.lines) * LINE_HEIGHT / 2
        for i, line in enumerate(node.lines):
            ext = ctx.text_extents(line)
            ctx.move_to(node.x - ext.x_advance / 2, top + (i + 0.5) * LINE_HEIGHT + FONT_SIZE * 0.35)
            ctx.show_text(line)
    for node in walk(root):
        if node.badge:
            draw_badge(ctx, node)
            set_font(ctx, FONT_SIZE, bold=True)


def draw_badge(ctx, node):
    set_font(ctx, 12, bold=True)
    w = ctx.text_extents(node.badge).x_advance + 14
    x, y = node.x + node.w / 2 - w + 6, node.y - node.h / 2 - 15
    rounded_rect(ctx, x, y, w, 20, 10)
    ctx.set_source_rgb(*node.mark)
    ctx.fill()
    ctx.set_source_rgb(1, 1, 1)
    ctx.move_to(x + 7, y + 14)
    ctx.show_text(node.badge)


def legend_entries(kinds):
    return [(text, fill) for k, (text, fill, _) in CATEGORIES.items() if k in kinds]


def draw_legend(ctx, x, y, entries):
    set_font(ctx, 14)
    for text, fill in entries:
        rounded_rect(ctx, x, y, 16, 16, 4)
        ctx.set_source_rgb(*fill)
        ctx.fill()
        ctx.set_source_rgb(*TEXT_PRIMARY)
        ctx.move_to(x + 22, y + 13)
        ctx.show_text(text)
        x += 22 + ctx.text_extents(text).x_advance + 24


def open_surface(output_path, width, height, scale):
    """Returns (surface, ctx, finish) for a .png/.pdf/.svg file, already painted white."""
    ext = output_path.rsplit(".", 1)[-1].lower()
    if ext == "pdf":
        surface = cairo.PDFSurface(output_path, width, height)
    elif ext == "svg":
        surface = cairo.SVGSurface(output_path, width, height)
    else:
        surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, int(width * scale), int(height * scale))
    ctx = cairo.Context(surface)
    if ext not in ("pdf", "svg"):
        ctx.scale(scale, scale)
    ctx.set_source_rgb(*BACKGROUND)
    ctx.paint()

    def finish():
        if ext in ("pdf", "svg"):
            surface.finish()
        else:
            surface.write_to_png(output_path)

    return surface, ctx, finish


def render(tree, output_path, title, scale, legend=True):
    top = MARGIN + (TITLE_HEIGHT if title else 0) + (LEGEND_HEIGHT if legend else 0)
    width, height = int(tree.width + 2 * MARGIN), int(top + tree.height + MARGIN)
    _, ctx, finish = open_surface(output_path, width, height, scale)
    if title:
        set_font(ctx, 28, bold=True)
        ctx.set_source_rgb(*TEXT_PRIMARY)
        ctx.move_to(MARGIN, MARGIN + 28)
        ctx.show_text(title)
    if legend:
        draw_legend(ctx, MARGIN, top - LEGEND_HEIGHT - 4, legend_entries(tree.kinds()))
    ctx.translate(MARGIN, top)
    tree.draw(ctx)
    finish()
    return width, height


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", nargs="?", default="baseline.txt")
    ap.add_argument("output", nargs="?")
    ap.add_argument("--max-depth", type=int)
    ap.add_argument("--highlight", action="append", default=[])
    ap.add_argument("--scale", type=float, default=3)
    ap.add_argument("--title")
    ap.add_argument("--bare", action="store_true", help="tree only: no title, no legend")
    args = ap.parse_args()

    output = args.output or args.input.rsplit(".", 1)[0] + ".png"
    title = "" if args.bare else args.title if args.title is not None else args.input.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    with open(args.input) as f:
        tree = Tree(f.read().strip(), args.max_depth)
    for path in args.highlight:
        tree.highlight(path)
    w, h = render(tree, output, title, args.scale, legend=not args.bare)
    print(f"Wrote {output} ({w}x{h} units)")


if __name__ == "__main__":
    main()
