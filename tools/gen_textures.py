"""Generates the mod's procedural textures at 4K.

Every texture in here is synthesised rather than painted, for two reasons: the geometry
references materials (brushed metal, carbon weave, lens glass), not artwork, so the right
source of truth is a material description; and it keeps the textures reproducible — rerun this
script and the exact same bytes come out (all noise is seeded).

Outputs, all 4096x4096 RGBA:
  - textures/entity/camera_parts.png      8x8 atlas of 512px material tiles (see
                                          CameraRenderer's tile constants for the index map)
  - textures/block/camera_dark.png        camera body: panelled magnesium, seams + screws
  - textures/block/camera_base.png        machined accessory metal, brushed + knurled bands
  - textures/models/armor/camera_rig_layer_1.png
                                          the worn shoulder rig (zones match ShoulderRigModel)

The atlas can be any resolution because CameraRenderer's box() UVs are normalised fractions of
the whole texture; 512px tiles just give those fractions real detail to land on.
"""

import math
import pathlib
import struct
import zlib

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/ndidisplays"

TILE = 512          # atlas tile edge, px
ATLAS = TILE * 8    # 4096
FULL = 4096         # standalone texture edge


# --------------------------------------------------------------------------- core helpers

def write_png(path: pathlib.Path, rgb: np.ndarray) -> int:
    """Writes an HxWx3 float array (0..255) as RGBA PNG. Returns the byte size."""
    h, w, _ = rgb.shape
    rgba = np.empty((h, w, 4), np.uint8)
    rgba[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    rgba[..., 3] = 255
    raw = np.zeros((h, 1 + w * 4), np.uint8)          # leading 0 = per-row filter byte
    raw[:, 1:] = rgba.reshape(h, w * 4)

    def chunk(tag: bytes, data: bytes) -> bytes:
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw.tobytes(), 9))
           + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    return len(png)


def vnoise(shape, cell, seed, octaves=3, persistence=0.55):
    """Seeded value noise in 0..1 — fBM over bilinear-upsampled random grids."""
    h, w = shape
    total = np.zeros(shape, np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        gh, gw = max(1, h // cell) + 2, max(1, w // cell) + 2
        g = np.random.default_rng(seed * 7919 + o * 101).random((gh, gw), np.float32)
        ys = np.linspace(0, gh - 2, h, dtype=np.float32)
        xs = np.linspace(0, gw - 2, w, dtype=np.float32)
        y0, x0 = ys.astype(np.int32), xs.astype(np.int32)
        fy, fx = (ys - y0)[:, None], (xs - x0)[None, :]
        a = g[y0][:, x0]
        b = g[y0][:, x0 + 1]
        c = g[y0 + 1][:, x0]
        d = g[y0 + 1][:, x0 + 1]
        total += amp * (a * (1 - fy) * (1 - fx) + b * (1 - fy) * fx
                        + c * fy * (1 - fx) + d * fy * fx)
        norm += amp
        amp *= persistence
        cell = max(2, cell // 2)
    return total / norm


def smooth_x(a: np.ndarray, radius: int) -> np.ndarray:
    """Box blur along x — stretches noise into brushed-metal streaks."""
    if radius <= 0:
        return a
    k = 2 * radius + 1
    pad = np.pad(a, ((0, 0), (radius, radius)), mode="wrap")
    c = np.cumsum(pad, axis=1)
    return (c[:, k - 1:] - np.concatenate(
        [np.zeros((a.shape[0], 1), a.dtype), c[:, :-k]], axis=1)) / k


def coords(shape):
    h, w = shape
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    return x, y


def tint(lum: np.ndarray, rgb) -> np.ndarray:
    """Expands a ~1.0-centred luminance multiplier into an HxWx3 image of `rgb`.

    The output is in 0..255 — lum scales the colour, it does not replace it. (An earlier
    version divided by 255 without scaling back, which rendered every tile that did not add
    absolute colour afterwards as near-black; the per-tile mean check in the verifier is what
    caught it.)"""
    out = np.empty((*lum.shape, 3), np.float32)
    for i in range(3):
        out[..., i] = lum * float(rgb[i])
    return out


def top_light(shape, strength):
    """Vertical light fall-off: the universal cue that makes flat colour read as a surface."""
    h, _ = shape
    return 1.0 + strength * (0.5 - np.linspace(0, 1, h, dtype=np.float32))[:, None]


# --------------------------------------------------------------------------- materials
#
# Each returns an SxSx3 float image (0..255). Feature placement is centre-weighted and
# fairly subtle on purpose: box() maps the *whole* tile onto every face, so a long thin face
# stretches the design — soft, distributed detail stretches gracefully where a single bold
# screw would smear into a streak.

def mat_panel(s, base, seed, screws=True, seam=True):
    """Painted magnesium panel: grain, seam cross, corner screws, edge AO."""
    lum = 1.0 + (vnoise((s, s), 48, seed) - 0.5) * 0.16
    x, y = coords((s, s))
    if seam:
        for pos in (0.5,):
            for d in (np.abs(x / s - pos), np.abs(y / s - pos)):
                lum *= np.where(d < 0.004, 0.62, 1.0)
                lum *= np.where((d >= 0.004) & (d < 0.008), 1.15, 1.0)
    edge = np.minimum(np.minimum(x, s - 1 - x), np.minimum(y, s - 1 - y)) / s
    lum *= 0.82 + 0.18 * np.clip(edge / 0.06, 0, 1)          # edge ambient occlusion
    img = tint(lum * top_light((s, s), 0.16), base)
    if screws:
        for cx, cy in [(0.16, 0.16), (0.84, 0.16), (0.16, 0.84), (0.84, 0.84)]:
            d = np.hypot(x - cx * s, y - cy * s)
            r = s * 0.028
            rim = np.clip(1 - np.abs(d - r) / (r * 0.5), 0, 1)
            hole = np.clip(1 - d / (r * 0.65), 0, 1)
            for i in range(3):
                img[..., i] = img[..., i] * (1 - 0.5 * hole) + rim * base[i] * 0.9
            slot = (np.abs((x - cx * s) - (y - cy * s)) < s * 0.006) & (d < r * 0.55)
            img[slot] *= 0.45
    return img


def mat_brushed(s, base, seed, streak=36):
    """Anisotropic brushed metal with a soft clear-coat band."""
    lum = 0.78 + smooth_x(vnoise((s, s), 6, seed, octaves=2) - 0.5, streak) * 1.5 + 0.11
    x, y = coords((s, s))
    band = np.exp(-((y / s - 0.32) ** 2) / 0.02) * 0.16       # coat reflection band
    return tint(np.clip(lum + band, 0, 2) * top_light((s, s), 0.12), base)


def mat_matte(s, base, seed):
    lum = 1.0 + (vnoise((s, s), 24, seed, octaves=4) - 0.5) * 0.10
    return tint(lum * top_light((s, s), 0.10), base)


def mat_lens(s, seed, highlight=(215, 232, 255), fringe=(70, 60, 190)):
    """Multicoated glass: deep radial body, coating fringe at the rim, twin speculars,
    and a faint iris-blade polygon."""
    x, y = coords((s, s))
    cx = cy = s / 2
    d = np.hypot(x - cx, y - cy) / (s / 2)
    body = 0.55 + 0.75 * (1 - d) ** 2          # dark rim, slightly lifted centre
    img = tint(body, (18, 22, 34))
    ring = np.clip(1 - np.abs(d - 0.86) / 0.10, 0, 1) ** 2    # coating fringe
    for i in range(3):
        img[..., i] += ring * fringe[i] * 0.55
    ang = np.arctan2(y - cy, x - cx)
    blades = 0.5 + 0.5 * np.cos(ang * 9)                       # 9-blade iris hint
    img *= (1 - 0.10 * np.clip(blades, 0, 1) * np.clip(1 - d, 0, 1))[..., None]
    for (hx, hy, hr, k) in [(0.36, 0.32, 0.16, 1.0), (0.62, 0.58, 0.06, 0.5)]:
        g = np.exp(-(((x / s - hx) ** 2 + (y / s - hy) ** 2)) / (hr ** 2 / 3)) * k
        for i in range(3):
            img[..., i] += g * highlight[i]
    img[d > 0.995] = (30, 30, 34)
    return np.clip(img, 0, 255)


def mat_glow(s, hot, cool, seed):
    """LED lamp: hot core over a dark housing, with a slight bloom halo."""
    x, y = coords((s, s))
    d = np.hypot(x - s / 2, y - s / 2) / (s / 2)
    core = np.clip(1 - d / 0.55, 0, 1) ** 1.5
    halo = np.clip(1 - d / 0.95, 0, 1) ** 3
    img = tint(np.ones((s, s), np.float32), cool)
    for i in range(3):
        img[..., i] = cool[i] * 0.25 + core * hot[i] + halo * hot[i] * 0.35
    return np.clip(img, 0, 255)


def mat_vents(s, base, seed, slots=7):
    img = mat_panel(s, base, seed, screws=False, seam=False)
    x, y = coords((s, s))
    fy = (y / s * slots) % 1.0
    inslot = (fy > 0.28) & (fy < 0.72) & (x / s > 0.10) & (x / s < 0.90)
    lip = (fy > 0.72) & (fy < 0.80) & (x / s > 0.10) & (x / s < 0.90)
    img[inslot] *= 0.35
    img[lip] *= 1.25
    return img


def mat_grip(s, base, seed):
    """Moulded rubber: diamond knurl with bevelled facets."""
    x, y = coords((s, s))
    u = (x + y) / s * 14
    v = (x - y) / s * 14
    facet = (np.sin(u * math.pi * 2) + np.sin(v * math.pi * 2)) * 0.5
    lum = 0.9 + facet * 0.22 + (vnoise((s, s), 20, seed) - 0.5) * 0.08
    return tint(lum * top_light((s, s), 0.10), base)


def mat_lcd(s, seed, base=(16, 26, 34), glow=(120, 220, 170)):
    """Status display: glass, scanlines, staggered readout blocks, bright bezel line."""
    img = tint(1.0 + (vnoise((s, s), 32, seed) - 0.5) * 0.08, base)
    x, y = coords((s, s))
    img *= (1 + 0.14 * ((y.astype(np.int32) // max(1, s // 96)) % 2))[..., None] * 0.94
    rng = np.random.default_rng(seed)
    rows, cols = 5, 8
    for r in range(rows):
        for c in range(cols):
            if rng.random() < 0.42:
                continue
            x0 = int(s * (0.10 + c * 0.10))
            y0 = int(s * (0.16 + r * 0.15))
            wl = int(s * 0.07 * (0.6 + rng.random() * 0.4))
            img[y0:y0 + s // 48, x0:x0 + wl] = glow
    border = (np.minimum(np.minimum(x, s - 1 - x), np.minimum(y, s - 1 - y)) < s * 0.015)
    img[border] = (60, 70, 78)
    return img


def mat_connector(s, base, seed):
    """I/O block: near-black housing with two banks of gold pins."""
    img = mat_matte(s, base, seed)
    x, y = coords((s, s))
    for row in (0.34, 0.66):
        for col in range(6):
            cx = s * (0.14 + col * 0.145)
            cy = s * row
            pin = (np.abs(x - cx) < s * 0.035) & (np.abs(y - cy) < s * 0.075)
            shine = np.clip(1 - np.abs(x - cx) / (s * 0.035), 0, 1)
            for i, g in enumerate((212, 168, 60)):
                ch = img[..., i]
                ch[pin] = g * (0.7 + 0.3 * shine[pin])
    return img


def mat_carbon(s, seed):
    """2x2 twill carbon: alternating warp/weft with anisotropic sheen."""
    x, y = coords((s, s))
    n = 18
    tow_x = (x / s * n).astype(np.int32)
    tow_y = (y / s * n).astype(np.int32)
    warp = ((tow_x + tow_y) % 2).astype(np.float32)
    fx = (x / s * n) % 1.0
    fy = (y / s * n) % 1.0
    sheen = np.where(warp > 0.5,
                     np.sin(fx * math.pi) ** 2,
                     np.sin(fy * math.pi) ** 2)
    lum = 0.55 + sheen * 0.5 + (vnoise((s, s), 16, seed) - 0.5) * 0.06
    return tint(lum * top_light((s, s), 0.18), (52, 56, 64))


def mat_hazard(s, seed):
    """45-degree black/yellow chevrons with grime and worn stripe edges."""
    x, y = coords((s, s))
    stripe = ((x + y) / s * 6).astype(np.int32) % 2
    wear = vnoise((s, s), 40, seed, octaves=4)
    img = np.where(stripe[..., None] > 0,
                   tint(np.ones((s, s), np.float32), (208, 168, 30)),
                   tint(np.ones((s, s), np.float32), (28, 28, 30)))
    img *= (0.8 + wear * 0.35)[..., None]
    return img


def mat_label(s, seed):
    """Equipment label: off-white plate, text line blocks, a barcode."""
    img = tint(1.0 + (vnoise((s, s), 64, seed) - 0.5) * 0.05, (222, 222, 214))
    rng = np.random.default_rng(seed)
    for r in range(4):
        y0 = int(s * (0.14 + r * 0.14))
        x0 = int(s * 0.10)
        while x0 < s * 0.85:
            wl = int(s * (0.04 + rng.random() * 0.10))
            img[y0:y0 + s // 40, x0:x0 + wl] = (60, 60, 66)
            x0 += wl + int(s * 0.03)
    bx = int(s * 0.10)
    while bx < s * 0.9:
        bw = max(2, int(rng.random() * s * 0.02))
        img[int(s * 0.74):int(s * 0.92), bx:bx + bw] = (30, 30, 34)
        bx += bw + max(2, int(rng.random() * s * 0.025))
    x, y = coords((s, s))
    border = (np.minimum(np.minimum(x, s - 1 - x), np.minimum(y, s - 1 - y)) < s * 0.02)
    img[border] = (150, 150, 146)
    return img


def mat_cable(s, base, seed):
    """Braided cable loom: diagonal over-under weave on dark rubber."""
    x, y = coords((s, s))
    braid = np.sin((x + y * 0.5) / s * math.pi * 26) * np.sin((y - x * 0.5) / s * math.pi * 8)
    lum = 0.85 + braid * 0.16 + (vnoise((s, s), 18, seed) - 0.5) * 0.08
    return tint(lum * top_light((s, s), 0.10), base)


def mat_gloss(s, base, seed):
    """Piano black: deep base with one hard window reflection."""
    img = tint(1.0 + (vnoise((s, s), 48, seed) - 0.5) * 0.05, base)
    x, y = coords((s, s))
    win = (np.abs(x / s - 0.30) < 0.10) & (y / s < 0.62) & (y / s > 0.10)
    img[win] += 46
    win2 = (np.abs(x / s - 0.46) < 0.03) & (y / s < 0.55) & (y / s > 0.14)
    img[win2] += 28
    return np.clip(img, 0, 255)


def mat_ir(s, seed):
    """IR window: near-black maroon glass with a faint emitter grid behind it."""
    img = tint(1.0 + (vnoise((s, s), 30, seed) - 0.5) * 0.06, (34, 18, 26))
    x, y = coords((s, s))
    gx = ((x / s * 6) % 1.0 - 0.5)
    gy = ((y / s * 6) % 1.0 - 0.5)
    dot = np.exp(-(gx ** 2 + gy ** 2) / 0.02)
    for i, g in enumerate((70, 26, 40)):
        img[..., i] += dot * g * 0.5
    return np.clip(img, 0, 255)


# --------------------------------------------------------------------------- the atlas

def build_atlas() -> np.ndarray:
    s = TILE
    tiles = {
        0: mat_panel(s, (52, 55, 61), 10),                                   # BODY
        1: mat_brushed(s, (132, 136, 144), 11),                              # BODY_LIGHT
        2: mat_matte(s, (26, 27, 30), 12),                                   # BLACK
        3: mat_lens(s, 13),                                                  # LENS
        4: mat_glow(s, (255, 70, 52), (60, 18, 16), 14),                     # TALLY
        5: mat_vents(s, (48, 51, 57), 15),                                   # VENT
        6: mat_brushed(s, (176, 180, 188), 16, streak=48),                   # SILVER
        7: mat_grip(s, (38, 39, 43), 17),                                    # GRIP
        8: mat_lcd(s, 18),                                                   # LCD
        9: mat_connector(s, (22, 23, 27), 19),                               # CONNECTOR
        10: mat_carbon(s, 20),                                               # CARBON
        11: mat_hazard(s, 21),                                               # HAZARD
        12: mat_glow(s, (86, 170, 255), (14, 26, 44), 22),                   # BLUE_LED
        13: mat_label(s, 23),                                                # LABEL
        14: mat_brushed(s, (214, 126, 34), 24, streak=30),                   # ORANGE
        15: mat_cable(s, (30, 30, 34), 25),                                  # CABLE
        16: mat_panel(s, (44, 47, 55), 26),                                  # PTZ_BODY
        17: mat_brushed(s, (150, 154, 162), 27),                             # PTZ_BODY_LIGHT
        18: mat_lens(s, 28, highlight=(160, 240, 255), fringe=(30, 140, 190)),  # PTZ_GLASS
        19: mat_glow(s, (60, 220, 255), (10, 30, 42), 29),                   # PTZ_RING
        20: mat_vents(s, (40, 43, 51), 30, slots=9),                         # PTZ_VENT
        21: mat_brushed(s, (198, 202, 210), 31, streak=56),                  # PTZ_SILVER
        22: mat_gloss(s, (16, 16, 20), 32),                                  # PTZ_GLOSS
        23: mat_ir(s, 33),                                                   # PTZ_IR
    }
    atlas = np.zeros((ATLAS, ATLAS, 3), np.float32)
    atlas[:] = (52, 55, 61)                     # unused tiles: neutral body grey, never magenta
    for idx, img in tiles.items():
        r, c = idx // 8, idx % 8
        atlas[r * s:(r + 1) * s, c * s:(c + 1) * s] = img
    return atlas


# --------------------------------------------------------- standalone camera body textures

def build_camera_dark() -> np.ndarray:
    """Panelled camera body at full canvas: 4x4 panels, seams, screws, diagonal sheen."""
    s = FULL
    cell = s // 4
    lum = 1.0 + (vnoise((s, s), 160, 41, octaves=5) - 0.5) * 0.14
    x, y = coords((s, s))
    lum *= 1.0 + 0.05 * np.sin((x + y) / s * math.pi)          # broad diagonal sheen
    ex = np.minimum(x % cell, cell - 1 - (x % cell))
    ey = np.minimum(y % cell, cell - 1 - (y % cell))
    edge = np.minimum(ex, ey)
    lum *= np.where(edge < cell * 0.006, 0.55, 1.0)
    lum *= np.where((edge >= cell * 0.006) & (edge < cell * 0.014), 1.16, 1.0)
    lum *= 0.85 + 0.15 * np.clip(edge / (cell * 0.10), 0, 1)
    img = tint(lum, (31, 30, 28))
    img = np.clip(img, 0, 255)
    for cy in range(4):
        for cx in range(4):
            px, py = cx * cell + cell - 90, cy * cell + 90
            d = np.hypot(x - px, y - py)
            r = 42.0
            rim = np.clip(1 - np.abs(d - r) / 18, 0, 1)
            hole = np.clip(1 - d / (r * 0.62), 0, 1)
            for i in range(3):
                img[..., i] = img[..., i] * (1 - 0.55 * hole) + rim * 52
    return img


def build_camera_base() -> np.ndarray:
    """Machined accessory metal: brushed grain with two knurled grip bands."""
    s = FULL
    lum = 0.9 + smooth_x(vnoise((s, s), 8, 51, octaves=2) - 0.5, 64) * 1.6
    x, y = coords((s, s))
    for band in (0.26, 0.70):
        inband = np.abs(y / s - band) < 0.08
        lum = np.where(inband, lum + np.sin(x / 34.0) * 0.16, lum)
    img = tint(np.clip(lum, 0, 2) * top_light((s, s), 0.10), (96, 95, 92))
    return np.clip(img, 0, 255)


# --------------------------------------------------------------- the worn rig (armor layer)

def build_armor() -> np.ndarray:
    """Zones match the 128-space layout ShoulderRigModel's texOffs point into, scaled x32."""
    k = FULL // 128
    img = np.zeros((FULL, FULL, 3), np.float32)

    def paint(zone, tile):
        x0, y0, x1, y1 = [v * k for v in zone]
        # each zone is painted at its native size so features scale with the zone
        img[y0:y1, x0:x1] = _resize(tile, (y1 - y0, x1 - x0))

    def _resize(tile, shape):
        h, w = shape
        ys = np.linspace(0, tile.shape[0] - 1, h).astype(np.int32)
        xs = np.linspace(0, tile.shape[1] - 1, w).astype(np.int32)
        return tile[ys][:, xs]

    paint((0, 0, 64, 64), mat_panel(TILE * 2, (26, 28, 32), 61))            # body / lens
    paint((64, 0, 128, 64), mat_brushed(TILE * 2, (74, 78, 86), 62))        # handle / rods
    paint((0, 64, 64, 96), mat_vents(TILE, (52, 56, 62), 63, slots=6))      # battery grille
    paint((64, 64, 96, 96), mat_brushed(TILE, (206, 122, 30), 64, streak=24))  # orange
    paint((96, 64, 128, 96), mat_glow(TILE, (255, 74, 58), (44, 16, 14), 65))  # REC lamp
    paint((0, 96, 64, 128), _knurl_ring())                                  # focus scale ring
    paint((64, 96, 128, 128), mat_lcd(TILE, 67))                            # monitor face
    return img


def _knurl_ring() -> np.ndarray:
    s = TILE
    x, y = coords((s, s))
    lum = 0.85 + np.sin(x / s * math.pi * 44) * 0.18 + (vnoise((s, s), 24, 66) - 0.5) * 0.06
    img = tint(lum * top_light((s, s), 0.10), (188, 168, 78))
    # focus ticks along the top edge, every third one long
    for i in range(22):
        tx = int(s * (0.02 + i * 0.045))
        tl = 0.16 if i % 3 == 0 else 0.09
        img[int(s * 0.04):int(s * tl), tx:tx + max(2, s // 128)] = (238, 232, 205)
    return img


# ------------------------------------------------------------------------------------ main

def main():
    out = [
        (ROOT / "textures/entity/camera_parts.png", build_atlas()),
        (ROOT / "textures/block/camera_dark.png", build_camera_dark()),
        (ROOT / "textures/block/camera_base.png", build_camera_base()),
        (ROOT / "textures/models/armor/camera_rig_layer_1.png", build_armor()),
    ]
    for path, img in out:
        n = write_png(path, img)
        print(f"  {path.name:32} {img.shape[1]}x{img.shape[0]}  {n:,} bytes")


if __name__ == "__main__":
    main()
