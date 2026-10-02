#!/usr/bin/env python3
"""Rasterise the launcher icon into legacy mipmap PNGs (API < 26).

Adaptive icons (res/mipmap-anydpi-v26) are drawn from vector drawables, but
pre-26 launchers need real bitmaps. This script draws the exact same artwork
with PIL so the two paths cannot drift apart.

Geometry mirrors res/drawable/ic_launcher_{background,foreground}.xml:
108x108 canvas, blue field, faint mesh, white ">" prompt and "_" cursor.
"""
import os
from PIL import Image, ImageDraw

BG = (0x25, 0x63, 0xEB)
MESH_LINE = (0x93, 0xC5, 0xFD)
MESH_NODE = (0xBF, 0xDB, 0xFE)
WHITE = (0xFF, 0xFF, 0xFF)

MESH_LINKS = [
    [(10, 14), (44, 8), (86, 14)],
    [(10, 14), (8, 50)],
    [(86, 14), (100, 52)],
    [(8, 50), (12, 96)],
    [(100, 52), (94, 94)],
    [(12, 96), (52, 100), (94, 94)],
]
MESH_NODES = [
    (10, 14), (44, 8), (86, 14), (8, 50), (100, 52), (12, 96), (52, 100), (94, 94),
]

# Densities Android wants for a launcher icon (mdpi is the 48dp baseline).
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def draw_icon(size: int) -> Image.Image:
    """Draw the icon at `size`x`size`; coordinates are scaled from the 108 canvas."""
    s = size / 108.0
    img = Image.new("RGBA", (size, size), BG + (255,))
    d = ImageDraw.Draw(img)

    def sc(pt):
        return (pt[0] * s, pt[1] * s)

    # Mesh: composited at partial alpha so it stays a background texture.
    mesh = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    md = ImageDraw.Draw(mesh)
    for link in MESH_LINKS:
        md.line([sc(p) for p in link], fill=MESH_LINE + (102,), width=max(1, round(1.6 * s)))
    r = 3.0 * s
    for n in MESH_NODES:
        x, y = sc(n)
        md.ellipse([x - r, y - r, x + r, y + r], fill=MESH_NODE + (204,))
    img.alpha_composite(mesh)

    # Prompt ">" and cursor "_".
    w = max(1, round(8 * s))
    prompt = [(32, 40), (48, 54), (32, 68)]
    cursor = [(56, 68), (76, 68)]
    d.line([sc(p) for p in prompt], fill=WHITE + (255,), width=w, joint="curve")
    d.line([sc(p) for p in cursor], fill=WHITE + (255,), width=w)
    # Round caps: a plain line ends flat, which looks wrong at small sizes.
    cap = w / 2.0
    for p in [prompt[0], prompt[-1], cursor[0], cursor[-1]]:
        x, y = sc(p)
        d.ellipse([x - cap, y - cap, x + cap, y + cap], fill=WHITE + (255,))
    return img


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res")
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(root, folder)
        os.makedirs(out_dir, exist_ok=True)
        img = draw_icon(size)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            path = os.path.join(out_dir, name)
            img.save(path, "PNG")
            print(f"wrote {path} ({size}x{size})")
    # Play-Store style 512 icon, handy for the repo README / release page.
    os.makedirs(os.path.join(root, "..", "..", "..", "..", "docs"), exist_ok=True)
    big = draw_icon(512)
    big.save(os.path.join(root, "..", "..", "..", "..", "docs", "icon-512.png"), "PNG")
    print("wrote docs/icon-512.png (512x512)")


if __name__ == "__main__":
    main()
