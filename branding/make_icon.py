"""Mob Map Icons mod icon: 32x32 pixel-art grid, upscaled to 128x128 (nearest).
Same style/helpers as hopper-release-kit/icons/make_icons.py.
Run: python branding/make_icon.py   -> src/main/resources/logo.png
"""
import os
import sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "src", "main", "resources")
N = 32


def canvas(bg):
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # rounded tile background with darker rim
    d.rounded_rectangle([0, 0, N - 1, N - 1], radius=5, fill=bg[1])
    d.rounded_rectangle([1, 1, N - 2, N - 2], radius=4, fill=bg[0])
    return im, d


def save(im, name):
    im.resize((128, 128), Image.NEAREST).save(os.path.join(OUT, f"{name}.png"))


GRASS, GRASS2 = (96, 150, 76), (84, 136, 66)
WATER, WATER2 = (74, 128, 196), (96, 150, 216)
SAND, DIRT = (218, 196, 134), (140, 104, 66)
RED, RED_L, RED_D, RED_O = (224, 66, 62), (250, 124, 106), (170, 38, 52), (88, 20, 34)
FACE_O = (104, 24, 40)
ZG, ZH, ZE, ZM = (124, 196, 96), (66, 124, 70), (24, 28, 40), (40, 70, 48)

FACE = ["hhhhhhhh",
        "gggggggg",
        "gEEggEEg",
        "gEEggEEg",
        "gggggggg",
        "ggmmmmgg",
        "gmmmmmmg",
        "gmggggmg"]
FACE_PAL = {"h": ZH, "g": ZG, "E": ZE, "m": ZM}


def draw_pin(im):
    sh = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    for dx, dy in ((2, 2),):
        sd.ellipse([6 + dx, 1 + dy, 25 + dx, 20 + dy], fill=(0, 0, 0, 90))
        sd.polygon([(6 + dx, 15 + dy), (25 + dx, 15 + dy), (15.5 + dx, 30 + dy)], fill=(0, 0, 0, 90))
    im.alpha_composite(sh)
    d = ImageDraw.Draw(im)
    # outline, then body
    d.ellipse([6, 1, 25, 20], fill=RED_O)
    d.polygon([(7, 15), (24, 15), (15.5, 30)], fill=RED_O)
    d.ellipse([7, 2, 24, 19], fill=RED)
    d.polygon([(8, 15), (23, 15), (15.5, 28)], fill=RED)
    d.polygon([(15.5, 16), (23, 15), (15.5, 28)], fill=RED_D)         # shaded right half of tail
    d.arc([8, 3, 23, 18], 190, 260, fill=RED_L)                        # highlight
    d.arc([8, 3, 23, 18], 10, 70, fill=RED_D)                          # shade
    # face window with zombie head
    d.ellipse([10, 5, 21, 16], fill=FACE_O)
    d.ellipse([11, 6, 20, 15], fill=ZG)
    for y, row in enumerate(FACE):
        for x, ch in enumerate(row):
            if (x in (0, 7)) and (y in (0, 7)):
                continue
            d.point((12 + x, 7 + y), fill=FACE_PAL[ch])


def draw_icon():
    im, d = canvas(((96, 150, 76), (40, 62, 44)))
    # map-tile terrain: grass checks, river, sand banks, dirt patch
    for gy in range(2, 30, 4):
        for gx in range(2, 30, 4):
            if ((gx // 4) + (gy // 4)) % 2:
                d.rectangle([gx, gy, gx + 3, gy + 3], fill=GRASS2)
    d.polygon([(1, 22), (1, 29), (6, 29), (30, 7), (30, 1), (24, 1)], fill=SAND)
    d.polygon([(1, 24), (1, 29), (4, 29), (29, 6), (29, 3), (26, 3)], fill=WATER)
    d.line([(3, 27), (27, 5)], fill=WATER2)
    d.rectangle([23, 23, 28, 28], fill=DIRT); d.rectangle([24, 24, 26, 25], fill=(112, 82, 52))
    d.rectangle([3, 4, 5, 6], fill=DIRT)
    # rounded-tile rim is redrawn so the terrain never leaks over the corners
    rim = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim)
    rd.rounded_rectangle([0, 0, N - 1, N - 1], radius=5, outline=(40, 62, 44), width=1)
    im.alpha_composite(rim)
    draw_pin(im)
    # clip to tile shape (terrain rectangles can cross the rounded corners)
    mask = Image.new("L", (N, N), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, N - 1, N - 1], radius=5, fill=255)
    clipped = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    clipped.paste(im, (0, 0), mask)
    return clipped


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    save(draw_icon(), "logo")
    print("ok", OUT)
