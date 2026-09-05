# -*- coding: utf-8 -*-
"""Generates the chain hoist's procedural textures.

Everything the hoist draws is authored here rather than painted by hand, so the palette
stays consistent across the housing, the chain and the remote, and a colour change is a
one-line edit instead of six image files. Writes PNGs with the standard library only.

    python tools/gen_hoist_textures.py
"""
import os
import random
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLOCK = os.path.join(ROOT, "src", "main", "resources", "assets", "ndidisplays",
                     "textures", "block")
ITEM = os.path.join(ROOT, "src", "main", "resources", "assets", "ndidisplays",
                    "textures", "item")

STEEL = [142, 147, 154, 255]
STEEL_DARK = [92, 96, 103, 255]
STEEL_LIGHT = [182, 187, 194, 255]
YELLOW = [245, 206, 31, 255]
YELLOW_DARK = [186, 155, 16, 255]
RED = [216, 35, 42, 255]
RED_DARK = [122, 16, 20, 255]
SHELL = [26, 27, 30, 255]
SHELL_LIGHT = [58, 61, 66, 255]
PLATE = [18, 19, 22, 255]
GREEN_LED = [78, 226, 108, 255]


def write_png(path, width, height, pixels):
    def chunk(tag, data):
        head = struct.pack(">I", len(data)) + tag + data
        return head + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b""
    for y in range(height):
        raw += b"\x00" + bytes(v for x in range(width) for v in pixels[y][x])
    blob = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(blob)
    print("wrote", os.path.relpath(path, ROOT))


def blank(width, height, rgba=(0, 0, 0, 0)):
    return [[list(rgba) for _ in range(width)] for _ in range(height)]


def shade(colour, delta):
    return [max(0, min(255, int(c + delta))) for c in colour[:3]] + [colour[3]]


def rect(pixels, x0, y0, x1, y1, colour):
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[y][x] = list(colour)


def hoist_body(rng):
    """Anthracite housing with cooling slots down the flanks."""
    size = 32
    base = (38, 40, 44, 255)
    px = blank(size, size, base)
    for y in range(size):
        for x in range(size):
            px[y][x] = shade(base, rng.randint(-4, 4) + (2 if x % 6 == 0 else 0))
    for row in (9, 13, 17):
        for x in range(5, 27):
            if x % 5 != 0:
                px[row][x] = [22, 23, 26, 255]
                px[row + 1][x] = [28, 29, 33, 255]
    for i in range(size):
        px[0][i] = [52, 55, 60, 255]
        px[size - 1][i] = [24, 25, 28, 255]
        px[i][0] = shade(base, -8)
        px[i][size - 1] = shade(base, -8)
    return size, px


def hoist_metal(rng):
    """Brushed steel for the clamp plate, shackle and bearing caps."""
    size = 16
    base = (152, 157, 164, 255)
    px = blank(size, size, base)
    for y in range(size):
        band = rng.randint(-6, 6)
        for x in range(size):
            px[y][x] = shade(base, band + rng.randint(-3, 3))
    for i in range(size):
        px[0][i] = [178, 183, 190, 255]
        px[size - 1][i] = [112, 116, 122, 255]
        px[i][0] = [128, 132, 138, 255]
        px[i][size - 1] = [128, 132, 138, 255]
    return size, px


def hoist_bag(rng):
    """Dark canvas chain bag with a hazard band along its top seam."""
    size = 32
    base = (25, 26, 29, 255)
    px = blank(size, size, base)
    for y in range(size):
        for x in range(size):
            weave = 3 if (x + y) % 2 == 0 else -2
            px[y][x] = shade(base, weave + rng.randint(-2, 2))
    for row in (12, 22):
        for x in range(size):
            px[row][x] = [17, 18, 20, 255]
    for y in range(5):
        for x in range(size):
            px[y][x] = list(YELLOW) if (x + y) % 8 < 4 else [20, 20, 22, 255]
    return size, px


def hoist_panel():
    """Pendant face on the housing: three lamps over three keys."""
    size = 16
    px = blank(size, size, (19, 20, 23, 255))
    for i in range(size):
        px[0][i] = list(SHELL_LIGHT)
        px[size - 1][i] = list(SHELL_LIGHT)
        px[i][0] = list(SHELL_LIGHT)
        px[i][size - 1] = list(SHELL_LIGHT)

    def dot(cx, cy, colour):
        rect(px, cx, cy, cx + 2, cy + 2, colour)

    dot(3, 3, GREEN_LED)
    dot(7, 3, (228, 72, 60, 255))
    dot(11, 3, (226, 176, 34, 255))
    for cx in (3, 7, 11):
        dot(cx, 9, (74, 78, 86, 255))
        px[10][cx] = [50, 53, 58, 255]
    return size, px


def hoist_hook(rng):
    """Forged steel, brighter down the middle where a hook catches the light."""
    size = 16
    base = (74, 78, 86, 255)
    px = blank(size, size, base)
    for y in range(size):
        for x in range(size):
            px[y][x] = shade(base, (16 if 6 <= x <= 9 else 0) + rng.randint(-4, 4))
    for i in range(size):
        px[0][i] = [96, 101, 110, 255]
        px[size - 1][i] = [48, 51, 57, 255]
        px[i][0] = [56, 59, 66, 255]
        px[i][size - 1] = [56, 59, 66, 255]
    return size, px


def chain_link():
    """Load chain.

    Two vertical strips a quarter turn apart, drawn on crossed quads. A continuous
    edge-on bar runs the full height of each strip and the flat rings sit over it, which
    is what makes consecutive links read as interlocked rather than as a stack of
    separate rings with gaps between them.
    """
    size = 32
    px = blank(size, size)

    def bar(x0):
        for y in range(size):
            px[y][x0 + 2] = list(STEEL)
            px[y][x0 + 3] = list(STEEL_DARK)

    def ring(x0, y0):
        for x in range(x0 + 1, x0 + 5):
            px[y0][x] = list(STEEL_LIGHT)
            px[y0 + 7][x] = list(STEEL_DARK)
        for y in range(y0 + 1, y0 + 7):
            px[y][x0] = list(STEEL)
            px[y][x0 + 5] = list(STEEL)
        px[y0 + 1][x0 + 1] = list(STEEL)
        px[y0 + 1][x0 + 4] = list(STEEL)
        px[y0 + 6][x0 + 1] = list(STEEL_DARK)
        px[y0 + 6][x0 + 4] = list(STEEL_DARK)

    bar(0)
    ring(0, 0)
    ring(0, 16)
    bar(6)
    ring(6, 8)
    ring(6, 24)
    return size, px


def hoist_remote():
    """Item icon: yellow belly-box, mushroom stop, two key rows, cable tail."""
    size = 16
    px = blank(size, size)
    rect(px, 4, 0, 12, 15, SHELL)
    for cx, cy in ((4, 0), (11, 0), (4, 14), (11, 14)):
        px[cy][cx] = [0, 0, 0, 0]
    for y in range(1, 14):
        px[y][4] = list(SHELL_LIGHT)

    rect(px, 8, 1, 11, 3, RED)
    px[1][8] = list(RED_DARK)
    px[1][10] = list(RED_DARK)
    px[2][9] = list(RED_DARK)
    px[2][6] = list(GREEN_LED)

    rect(px, 5, 4, 11, 13, YELLOW)
    for y in range(4, 13):
        px[y][10] = list(YELLOW_DARK)

    rect(px, 6, 5, 8, 7, SHELL)
    rect(px, 8, 5, 10, 7, SHELL)
    rect(px, 6, 8, 10, 9, SHELL)
    rect(px, 6, 10, 10, 11, PLATE)
    rect(px, 7, 15, 9, 16, SHELL)
    return size, px


def main():
    rng = random.Random(20260901)
    for name, builder in (
        ("hoist_body", lambda: hoist_body(rng)),
        ("hoist_metal", lambda: hoist_metal(rng)),
        ("hoist_bag", lambda: hoist_bag(rng)),
        ("hoist_panel", hoist_panel),
        ("hoist_hook", lambda: hoist_hook(rng)),
        ("chain_link", chain_link),
    ):
        size, px = builder()
        write_png(os.path.join(BLOCK, name + ".png"), size, size, px)

    size, px = hoist_remote()
    write_png(os.path.join(ITEM, "hoist_remote.png"), size, size, px)


if __name__ == "__main__":
    main()
