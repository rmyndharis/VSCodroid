#!/usr/bin/env python3
"""Renders the web-client branding icons from the Android launcher icon.

The server ships resources/server/{code-192.png,code-512.png,favicon.ico} to the
browser as the PWA icon set. Upstream those are Microsoft's VS Code icon, which
is copyrighted and cannot travel with this app, so they are regenerated here from
our own adaptive launcher icon.

Run when the launcher icon changes; the outputs are committed:

    python3 scripts/generate-branding-icons.py

Requires Pillow. The adaptive icon is a 108dp canvas whose visible area is the
central 72dp, so a faithful square icon is the canvas rendered at size * 108/72
and centre-cropped; rendering the foreground full-bleed instead would leave the
artwork noticeably too small.
"""

import pathlib
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

ROOT = pathlib.Path(__file__).resolve().parent.parent
FOREGROUND = ROOT / "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
OUT_DIR = ROOT / "branding/server"

# res/drawable/ic_launcher_background.xml
BACKGROUND = (0xFF, 0xFF, 0xFF, 0xFF)

ADAPTIVE_CANVAS_DP = 108
ADAPTIVE_VISIBLE_DP = 72


def render(size: int) -> Image.Image:
    canvas_px = round(size * ADAPTIVE_CANVAS_DP / ADAPTIVE_VISIBLE_DP)
    canvas = Image.new("RGBA", (canvas_px, canvas_px), BACKGROUND)

    foreground = Image.open(FOREGROUND).convert("RGBA")
    foreground = foreground.resize((canvas_px, canvas_px), Image.Resampling.LANCZOS)
    canvas.alpha_composite(foreground)

    inset = (canvas_px - size) // 2
    return canvas.crop((inset, inset, inset + size, inset + size))


def main() -> None:
    if not FOREGROUND.exists():
        sys.exit(f"launcher foreground not found: {FOREGROUND}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for size in (192, 512):
        path = OUT_DIR / f"code-{size}.png"
        render(size).save(path, "PNG", optimize=True)
        print(f"  {path.relative_to(ROOT)}  {size}x{size}  {path.stat().st_size} bytes")

    # Browsers pick the size they want out of the .ico, so ship the usual set.
    favicon = OUT_DIR / "favicon.ico"
    render(256).save(
        favicon, "ICO", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    )
    print(f"  {favicon.relative_to(ROOT)}  multi-size  {favicon.stat().st_size} bytes")


if __name__ == "__main__":
    main()
