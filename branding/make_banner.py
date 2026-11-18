"""README banner (1280x640) for Mob Map Icons. Run: python branding/make_banner.py -> branding/banner.png
Draws on a 640x320 grid (2x upscale at the end). Uses the mod icon from make_icon.py, a hand-made
5x7 bitmap font and five generic pixel mob heads. Pillow only."""
import math
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import make_icon  # noqa: E402

OUT_BANNER = os.path.join(HERE, "banner.png")
INK = (26, 30, 44)
TERRAIN = {
    "g": ((92, 150, 74), (84, 140, 66)),
    "G": ((108, 168, 82), (98, 158, 76)),
    "d": ((128, 96, 62), (118, 88, 56)),
    "w": ((66, 116, 190), (76, 128, 204)),
    "s": ((210, 188, 128), (198, 176, 116)),
}


def hash01(x, y, seed=7):
    n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def cell(cx, cy):
    v = math.sin(cx * 0.45 + 1.0) + math.cos(cy * 0.8) + math.sin((cx + cy) * 0.28) + hash01(cx, cy, 3) * 0.25
    if v < -1.15:
        return "w"
    if v < -0.9:
        return "s"
    if v < 0.75:
        return "g" if hash01(cx, cy, 5) > 0.25 else "G"
    if v < 1.25:
        return "G"
    return "d"


def terrain(w, h, dim):
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            base, alt = TERRAIN[cell(x // 8, y // 8)]
            c = alt if hash01(x, y) < 0.35 else base
            if x % 8 == 0 or y % 8 == 0:
                c = tuple(int(v * 0.88) for v in c)
            px[x, y] = tuple(int(v * dim) for v in c) + (255,)
    return img


# ---- 12x12 generic mob heads -------------------------------------------------------------------
SPRITES = {
    "zombie": ({"O": (30, 62, 40), "h": (56, 108, 62), "g": (100, 168, 84), "G": (126, 192, 104),
                "E": INK, "m": (48, 84, 52)},
               [".OOOOOOOOOO.", "OhhhhhhhhhhO", "OhhhhhhhhhhO", "OggggggggggO", "OggEEggEEggO", "OggEEggEEggO",
                "OgGgggggGggO", "OgggmmmmgggO", "OggmmmmmmggO", "OggmgggmggGO", "OggggggggggO", ".OOOOOOOOOO."]),
    "pig": ({"O": (110, 48, 62), "p": (244, 166, 170), "P": (255, 192, 190), "S": (214, 110, 124),
             "n": (120, 50, 66), "E": INK},
            [".OOOOOOOOOO.", "OppppppppppO", "OpPPppppPPpO", "OppppppppppO", "OppEEppEEppO", "OppEEppEEppO",
             "OppSSSSSSppO", "OppSnSSnSppO", "OppSSSSSSppO", "OppppppppppO", "OppppppppppO", ".OOOOOOOOOO."]),
    "creeper": ({"O": (24, 54, 32), "g": (84, 176, 74), "G": (112, 200, 92), "t": (62, 140, 58), "E": INK},
                [".OOOOOOOOOO.", "OgGggtgggGgO", "OtggGggtgggO", "OgEEEggEEEgO", "OgEEEggEEEgO", "OGggEEEEgggO",
                 "OggEEEEEEggO", "OtgEEEEEEgtO", "OggEEggEEgGO", "OgGggtgggggO", "OggtggGggtgO", ".OOOOOOOOOO."]),
    "bat": ({"O": (28, 20, 46), "w": (112, 86, 150), "W": (146, 118, 186), "E": (240, 80, 80),
             "f": (250, 250, 240)},
            ["............", "............", "OO..O..O..OO", "OWO.OOOO.OWO", "OwwOwwwwOwwO", "OwwOEwwEOwwO",
             "OwwwwwwwwwwO", "OwOwwfwfwOwO", ".O.OwwwwO.O.", "....OOOO....", "............", "............"]),
    "slime": ({"O": (36, 90, 52), "s": (116, 204, 104), "S": (170, 236, 150), "E": INK, "m": (48, 120, 66)},
              ["............", "..OOOOOOOO..", ".OssssssssO.", "OsSSsssssssO", "OssEEssEEssO", "OssEEssEEssO",
               "OssssssssssO", "OsssmmmmsssO", "OssssssssssO", ".OssssssssO.", "..OOOOOOOO..", "............"]),
}


def sprite_img(name):
    pal, rows = SPRITES[name]
    assert len(rows) == 12 and all(len(r) == 12 for r in rows), name
    img = Image.new("RGBA", (12, 12))
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = pal[ch] + (255,)
    return img


def paste_scaled(dst, src, x, y, s):
    big = src.resize((src.width * s, src.height * s), Image.NEAREST)
    sh = Image.new("RGBA", big.size, (0, 0, 0, 0))
    spx, bpx = sh.load(), big.load()
    for yy in range(big.height):
        for xx in range(big.width):
            if bpx[xx, yy][3]:
                spx[xx, yy] = (0, 0, 0, 90)
    dst.alpha_composite(sh, (x + s, y + s))
    dst.alpha_composite(big, (x, y))


# ---- bitmap font: 5 wide, 9 tall (x-height rows 2..6, descenders rows 7..8) ---------------------
def g5(rows, top=0):
    return ["00000"] * top + rows


FONT = {
    "M": g5(["10001", "11011", "10101", "10101", "10001", "10001", "10001"]),
    "I": g5(["11111", "00100", "00100", "00100", "00100", "00100", "11111"]),
    "R": g5(["11110", "10001", "10001", "11110", "10100", "10010", "10001"]),
    "o": g5(["01110", "10001", "10001", "10001", "01110"], 2),
    "b": g5(["10000", "10000", "11110", "10001", "10001", "10001", "11110"]),
    "a": g5(["01110", "00001", "01111", "10001", "01111"], 2),
    "p": g5(["11110", "10001", "10001", "10001", "11110", "10000", "10000"], 2),
    "c": g5(["01110", "10001", "10000", "10001", "01110"], 2),
    "n": g5(["10110", "11001", "10001", "10001", "10001"], 2),
    "s": g5(["01111", "10000", "01110", "00001", "11110"], 2),
    "e": g5(["01110", "10001", "11111", "10000", "01110"], 2),
    "l": g5(["01100", "00100", "00100", "00100", "00100", "00100", "01110"]),
    "d": g5(["00001", "00001", "01111", "10001", "10001", "10001", "01111"]),
    "m": g5(["11010", "10101", "10101", "10101", "10101"], 2),
    "r": g5(["10110", "11001", "10000", "10000", "10000"], 2),
    "u": g5(["10001", "10001", "10001", "10011", "01101"], 2),
    "y": g5(["10001", "10001", "10001", "01111", "00001", "10001", "01110"], 2),
}
SPACE_W = 3


def draw_text(img, s, x, y, scale, colors):
    px = img.load()
    pts, cx = [], x
    for i, ch in enumerate(s):
        if ch == " ":
            cx += SPACE_W * scale
            continue
        col = colors[i] if isinstance(colors, list) else colors
        for ry, row in enumerate(FONT[ch]):
            for rx, bit in enumerate(row):
                if bit == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            pts.append((cx + rx * scale + sx, y + ry * scale + sy, col))
        cx += 6 * scale
    filled = {(a, b) for a, b, _ in pts}
    o = max(1, scale // 3)  # outline thickness

    def put(a, b, c):
        if 0 <= a < img.width and 0 <= b < img.height:
            px[a, b] = c + (255,)

    for a, b in filled:
        for dx in range(-o, o + 1):
            for dy in range(-o, o + 1):
                n = (a + dx, b + dy + o * 2)
                if n not in filled:
                    put(n[0], n[1], (14, 16, 26))
    for a, b in filled:
        for dx in range(-o, o + 1):
            for dy in range(-o, o + 1):
                n = (a + dx, b + dy)
                if n not in filled:
                    put(n[0], n[1], INK)
    for a, b, c in pts:
        put(a, b, c)


def make_banner():
    W, H = 640, 320
    bg = terrain(W // 2, H // 2, 0.62).resize((W, H), Image.NEAREST)
    img = bg
    panel = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ppx = panel.load()
    for y in range(36, H - 36):
        for x in range(12, W - 12):
            ppx[x, y] = (12, 16, 28, 120)
    img.alpha_composite(panel)
    icon = make_icon.draw_icon().resize((160, 160), Image.NEAREST)
    img.alpha_composite(icon, (36, 80))
    tx = 222
    cream, red, mint = (255, 244, 214), (255, 118, 104), (170, 232, 130)
    cols = [cream, cream, cream, cream, red, red, red, cream, mint, mint, mint, mint, mint]
    draw_text(img, "Mob Map Icons", tx, 78, 5, cols)
    draw_text(img, "Real mob models on your map", tx, 138, 2, (226, 232, 214))
    x = tx
    for n in ["zombie", "pig", "creeper", "bat", "slime"]:
        paste_scaled(img, sprite_img(n), x, 182, 5)
        x += 60 + 12
    img.resize((W * 2, H * 2), Image.NEAREST).save(OUT_BANNER)


if __name__ == "__main__":
    make_banner()
    print("ok", OUT_BANNER)
