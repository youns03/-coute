#!/usr/bin/env python3
"""Fail CI when bundled fonts lose the glyphs required by Écoute."""

from pathlib import Path
import sys

from fontTools.ttLib import TTFont

FONT_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "font"
REQUIRED = {
    # Arabic letters, hamza forms, tatweel and the common harakat used by
    # reader labels and translated examples. Readex Pro also carries the
    # Latin/French set so mixed-direction strings do not silently fall back.
    "readex_pro": tuple(
        ord(char)
        for char in (
            "ءآأؤإئابةتثجحخدذرزسشصضطظعغـفقكلمنهويى"
            "ًٌٍَُِّْ"
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ"
            "0123456789"
        )
    ),
    "newsreader": tuple(
        ord(char)
        for char in (
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ"
            "0123456789"
        )
    ),
}


def codepoints(font_path: Path) -> set[int]:
    font = TTFont(font_path, lazy=False)
    return {codepoint for table in font["cmap"].tables for codepoint in table.cmap}


def main() -> int:
    failures: list[str] = []
    for prefix, required in REQUIRED.items():
        paths = sorted(FONT_DIR.glob(f"{prefix}*.ttf"))
        if not paths:
            failures.append(f"no fonts found for {prefix}")
            continue
        for path in paths:
            present = codepoints(path)
            missing = [f"U+{cp:04X}" for cp in required if cp not in present]
            if missing:
                failures.append(f"{path.name}: missing {', '.join(missing)}")
            else:
                print(f"OK {path.name}: {len(present)} code points")

    if failures:
        print("Font coverage check failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
