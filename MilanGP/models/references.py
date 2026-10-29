#!/usr/bin/env python3
"""IEEE reference list as an A4 PDF.

Usage:
    python3 references.py [output.pdf]
"""

import sys

import cairo
import gi

gi.require_version("Pango", "1.0")
gi.require_version("PangoCairo", "1.0")
from gi.repository import Pango, PangoCairo  # noqa: E402

# Pango markup: <i> for journal / proceedings names
REFERENCES = [
    "S. Ontanon, “The Combinatorial Multi-Armed Bandit Problem and Its Application to Real-Time Strategy "
    "Games,” <i>Proc. AAAI Conf. Artif. Intell. Interact. Digit. Entertain.</i>, vol. 9, no. 1, pp. 58–64, "
    "Oct. 2013, doi: 10.1609/aiide.v9i1.12681.",
    "Y. Jin, G. Slabaugh, and S. Lucas, “Adapter-RL: Adaptation of Any Agent Using Reinforcement Learning,” "
    "<i>IEEE Trans. Games</i>, vol. 18, no. 1, pp. 173–184, Mar. 2026, doi: 10.1109/TG.2025.3628606.",
    "D. Scheepers and N. Pillay, “A Structure-Based Genetic Programming Generation Constructive "
    "Hyper-Heuristic with Transfer Learning for Combinatorial Optimisation,” <i>Genet. Program. Evolvable "
    "Mach.</i>, vol. 27, no. 1, Art. no. 2, Jun. 2026, doi: 10.1007/s10710-025-09528-3.",
    "W. La Cava, L. Spector, and K. Danai, “Epsilon-Lexicase Selection for Regression,” in <i>Proc. Genet. "
    "Evol. Comput. Conf. (GECCO ’16)</i>, Denver, CO, USA, Jul. 2016, pp. 741–748, "
    "doi: 10.1145/2908812.2908898.",
]

PAGE_W, PAGE_H = 595, 842       # A4 in points
MARGIN = 64
LABEL_W = 34                    # width of the "[n]" column (hanging indent)
FONT = "Serif"


def layout(ctx, markup, size, width):
    lay = PangoCairo.create_layout(ctx)
    lay.set_font_description(Pango.FontDescription(f"{FONT} {size}"))
    lay.set_width(int(width * Pango.SCALE))
    lay.set_wrap(Pango.WrapMode.WORD_CHAR)
    lay.set_markup(markup, -1)
    return lay


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "figures/references.pdf"
    png = out.lower().endswith(".png")
    if png:  # 3x for print; white background
        surface = cairo.ImageSurface(cairo.FORMAT_RGB24, PAGE_W * 3, PAGE_H * 3)
        ctx = cairo.Context(surface)
        ctx.scale(3, 3)
        ctx.set_source_rgb(1, 1, 1)
        ctx.paint()
    else:
        surface = cairo.PDFSurface(out, PAGE_W, PAGE_H)
        ctx = cairo.Context(surface)
    ctx.set_source_rgb(0, 0, 0)

    y = MARGIN
    heading = layout(ctx, "<b>References</b>", 15, PAGE_W - 2 * MARGIN)
    ctx.move_to(MARGIN, y)
    PangoCairo.show_layout(ctx, heading)
    y += heading.get_pixel_size()[1] + 16

    text_w = PAGE_W - 2 * MARGIN - LABEL_W
    for i, ref in enumerate(REFERENCES, 1):
        ctx.move_to(MARGIN, y)
        PangoCairo.show_layout(ctx, layout(ctx, f"[{i}]", 10.5, LABEL_W))
        body = layout(ctx, ref, 10.5, text_w)
        ctx.move_to(MARGIN + LABEL_W, y)
        PangoCairo.show_layout(ctx, body)
        y += body.get_pixel_size()[1] + 10

    if png:
        surface.write_to_png(out)
    else:
        surface.finish()
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
