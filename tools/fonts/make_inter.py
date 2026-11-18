"""Makes the app's Inter fonts (app/src/main/res/font/inter_*.ttf) from Inter Variable.

The source is the woff2 files of @fontsource-variable/inter, the package nesty.trade uses. Each
weight the app uses becomes one static TrueType font: every subset below decompressed from woff2
(lossless), pinned at that weight, then merged into one file. Android reads neither woff2 nor a
unicode-range fallback between an app's own fonts, hence one file per weight.

Needs fontTools and brotli (pip install fonttools brotli).

    python make_inter.py <@fontsource-variable/inter>/files <out dir>
"""
import os
import sys

from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

SUBSETS = ["latin", "latin-ext", "vietnamese", "greek", "cyrillic"]
WEIGHTS = {400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold"}


def main(src: str, out: str) -> None:
    work = os.path.join(out, "work")
    os.makedirs(work, exist_ok=True)
    for wght, style in WEIGHTS.items():
        parts = []
        for subset in SUBSETS:
            font = TTFont(os.path.join(src, f"inter-{subset}-wght-normal.woff2"))
            font.flavor = None
            static = instancer.instantiateVariableFont(font, {"wght": wght})
            if "STAT" in static:
                del static["STAT"]
            part = os.path.join(work, f"{subset}-{wght}.ttf")
            static.save(part)
            parts.append(part)
        merged = Merger().merge(parts)
        for rec in merged["name"].names:
            if rec.nameID in (1, 16):
                rec.string = "Inter"
            elif rec.nameID in (2, 17):
                rec.string = style
            elif rec.nameID == 4:
                rec.string = f"Inter {style}"
            elif rec.nameID == 6:
                rec.string = f"Inter-{style}"
        merged["OS/2"].usWeightClass = wght
        path = os.path.join(out, f"inter_{style.lower()}.ttf")
        merged.save(path)
        print(f"{path}: {os.path.getsize(path):,} bytes, {len(merged.getBestCmap())} characters")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
