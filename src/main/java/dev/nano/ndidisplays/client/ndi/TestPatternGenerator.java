package dev.nano.ndidisplays.client.ndi;

import java.nio.ByteBuffer;

/**
 * Draws engineering test patterns straight into a BGRA buffer, for the router to publish when it
 * is set to generate rather than repatch.
 *
 * Built on the CPU on purpose. Every other source in this mod is a rendered frame read back off
 * the GPU, but a test pattern is exactly the thing you reach for when you suspect the GPU path —
 * so it deliberately shares nothing with it. It also means a pattern can be produced with no
 * world loaded, no render thread and no capture target.
 *
 * The overlay carries what an engineer actually needs off a pattern: the source name, the real
 * resolution and rate, and a counter that proves the feed is live rather than a frozen frame.
 * The glyphs come from the small bitmap font below rather than Minecraft's, since this runs
 * outside any render pass.
 */
public final class TestPatternGenerator {

    /** Pattern ids, mirrored in the router's GUI. 0 means "repatch a real source instead". */
    public static final int PATTERN_OFF = 0;
    public static final int BARS = 1;
    public static final int GRID = 2;
    public static final int RAMP = 3;
    public static final int MOTION = 4;
    public static final int PATTERN_COUNT = 5;

    /** SMPTE-style top bars: 75% white, yellow, cyan, green, magenta, red, blue. */
    private static final int[] BAR_COLOURS = {
            0xBFBFBF, 0xBFBF00, 0x00BFBF, 0x00BF00, 0xBF00BF, 0xBF0000, 0x0000BF,
    };

    private TestPatternGenerator() {
    }

    public static String patternName(int pattern) {
        return switch (pattern) {
            case BARS -> "Colour Bars";
            case GRID -> "Alignment Grid";
            case RAMP -> "Greyscale Ramp";
            case MOTION -> "Motion Check";
            default -> "Off";
        };
    }

    /**
     * Fills {@code out} with one frame, BGRA, top-down, tightly packed.
     *
     * @param frame counter driving anything that moves, so a stalled sender is visible
     */
    public static void render(ByteBuffer out, int width, int height, int pattern,
                              String sourceName, int fps, long frame) {
        int[] px = new int[width * height];
        switch (pattern) {
            case GRID -> grid(px, width, height);
            case RAMP -> ramp(px, width, height);
            case MOTION -> motion(px, width, height, frame);
            default -> bars(px, width, height);
        }
        overlay(px, width, height, sourceName, fps, pattern, frame);

        out.clear();
        for (int rgb : px) {
            out.put((byte) (rgb & 0xFF));            // B
            out.put((byte) ((rgb >> 8) & 0xFF));     // G
            out.put((byte) ((rgb >> 16) & 0xFF));    // R
            out.put((byte) 0xFF);                    // X — BGRX ignores alpha
        }
        out.flip();
    }

    // ------------------------------------------------------------------ patterns

    /** Colour bars over a pluge strip: the standard "is this feed sane" picture. */
    private static void bars(int[] px, int w, int h) {
        int barsBottom = h * 2 / 3;
        for (int y = 0; y < barsBottom; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = BAR_COLOURS[Math.min(BAR_COLOURS.length - 1,
                        x * BAR_COLOURS.length / w)];
            }
        }
        // Reverse bars, then a pluge: black steps either side of black to set brightness by eye.
        int mid = barsBottom + (h - barsBottom) / 3;
        for (int y = barsBottom; y < mid; y++) {
            for (int x = 0; x < w; x++) {
                int i = BAR_COLOURS.length - 1 - x * BAR_COLOURS.length / w;
                px[y * w + x] = BAR_COLOURS[Math.max(0, Math.min(BAR_COLOURS.length - 1, i))] / 3;
            }
        }
        int[] pluge = {0x000000, 0x0A0A0A, 0x000000, 0x141414, 0x000000, 0xFFFFFF};
        for (int y = mid; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = pluge[Math.min(pluge.length - 1, x * pluge.length / w)];
            }
        }
    }

    /**
     * Alignment grid: 100px cells, a centre cross, corner markers and a 90% safe-area box —
     * everything needed to spot a stretched, cropped or offset feed at a glance.
     */
    private static void grid(int[] px, int w, int h) {
        java.util.Arrays.fill(px, 0x0A0A0C);
        int cell = 100;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean line = x % cell == 0 || y % cell == 0;
                boolean major = x % (cell * 5) == 0 || y % (cell * 5) == 0;
                if (major) {
                    px[y * w + x] = 0x9AA0A6;
                } else if (line) {
                    px[y * w + x] = 0x3A3F44;
                }
            }
        }
        // one-pixel border, so any cropping shows immediately
        for (int x = 0; x < w; x++) {
            px[x] = 0xE0E0E0;
            px[(h - 1) * w + x] = 0xE0E0E0;
        }
        for (int y = 0; y < h; y++) {
            px[y * w] = 0xE0E0E0;
            px[y * w + w - 1] = 0xE0E0E0;
        }
        // centre cross and safe area
        int cx = w / 2;
        int cy = h / 2;
        for (int x = 0; x < w; x++) {
            px[cy * w + x] = 0xE0E0E0;
        }
        for (int y = 0; y < h; y++) {
            px[y * w + cx] = 0xE0E0E0;
        }
        rect(px, w, h, w / 20, h / 20, w - w / 20, h - h / 20, 0xC03030);
    }

    /** Greyscale ramp with stepped wedges under it: gamma and banding at a glance. */
    private static void ramp(int[] px, int w, int h) {
        int split = h * 3 / 5;
        for (int y = 0; y < split; y++) {
            for (int x = 0; x < w; x++) {
                int v = x * 255 / Math.max(1, w - 1);
                px[y * w + x] = (v << 16) | (v << 8) | v;
            }
        }
        int steps = 16;
        for (int y = split; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int step = x * steps / w;
                int v = step * 255 / (steps - 1);
                px[y * w + x] = (v << 16) | (v << 8) | v;
            }
        }
    }

    /**
     * Motion check: a bar sweeping across a dark field with a marked centre line.
     *
     * A static pattern cannot tell you whether a feed is live or frozen on its last frame, which
     * is the failure this mod has hit most often — hence a pattern whose whole job is to move.
     */
    private static void motion(int[] px, int w, int h, long frame) {
        java.util.Arrays.fill(px, 0x101014);
        int barW = Math.max(8, w / 24);
        int span = w + barW;
        int x0 = (int) ((frame * Math.max(2, w / 90)) % span) - barW;
        for (int y = h / 6; y < h - h / 6; y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x0 + barW); x++) {
                px[y * w + x] = 0xF0F0F0;
            }
        }
        for (int x = 0; x < w; x++) {
            px[(h / 2) * w + x] = 0x505860;
        }
        // Tick marks every tenth of the width, to time the sweep.
        for (int i = 0; i <= 10; i++) {
            int tx = Math.min(w - 1, i * w / 10);
            for (int y = h / 2 - h / 40; y < h / 2 + h / 40; y++) {
                px[y * w + tx] = 0x8A9298;
            }
        }
    }

    private static void rect(int[] px, int w, int h, int x0, int y0, int x1, int y1, int colour) {
        for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
            if (y0 >= 0 && y0 < h) {
                px[y0 * w + x] = colour;
            }
            if (y1 - 1 >= 0 && y1 - 1 < h) {
                px[(y1 - 1) * w + x] = colour;
            }
        }
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            if (x0 >= 0 && x0 < w) {
                px[y * w + x0] = colour;
            }
            if (x1 - 1 >= 0 && x1 - 1 < w) {
                px[y * w + x1 - 1] = colour;
            }
        }
    }

    // ------------------------------------------------------------------ overlay

    /** The information block: what this feed is, how big, how fast, and whether it is moving. */
    private static void overlay(int[] px, int w, int h, String sourceName, int fps,
                               int pattern, long frame) {
        int scale = Math.max(2, w / 320);
        int lineH = (GLYPH_H + 2) * scale;
        String[] lines = {
                sourceName.toUpperCase(java.util.Locale.ROOT),
                w + "X" + h + "  " + fps + "FPS",
                patternName(pattern).toUpperCase(java.util.Locale.ROOT),
                "FRAME " + frame,
        };
        int boxW = 0;
        for (String line : lines) {
            boxW = Math.max(boxW, line.length() * (GLYPH_W + 1) * scale);
        }
        int padding = 6 * scale;
        int bx = w / 20 + padding;
        int by = h / 20 + padding;
        // Dim plate behind the text so it stays readable over white bars.
        for (int y = by - padding / 2; y < Math.min(h, by + lines.length * lineH + padding / 2); y++) {
            for (int x = bx - padding / 2; x < Math.min(w, bx + boxW + padding / 2); x++) {
                if (x >= 0 && y >= 0) {
                    int c = px[y * w + x];
                    px[y * w + x] = ((c >> 2) & 0x3F3F3F);
                }
            }
        }
        for (int i = 0; i < lines.length; i++) {
            text(px, w, h, bx, by + i * lineH, lines[i], scale, 0xF0F4F8);
        }
    }

    private static void text(int[] px, int w, int h, int x, int y, String s, int scale, int colour) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            glyph(px, w, h, cx, y, s.charAt(i), scale, colour);
            cx += (GLYPH_W + 1) * scale;
        }
    }

    private static void glyph(int[] px, int w, int h, int x, int y, char c, int scale, int colour) {
        int idx = GLYPH_ORDER.indexOf(Character.toUpperCase(c));
        if (idx < 0) {
            return;
        }
        for (int row = 0; row < GLYPH_H; row++) {
            int bits = GLYPHS[idx * GLYPH_H + row];
            for (int col = 0; col < GLYPH_W; col++) {
                if ((bits & (1 << (GLYPH_W - 1 - col))) == 0) {
                    continue;
                }
                for (int sy = 0; sy < scale; sy++) {
                    int py = y + row * scale + sy;
                    if (py < 0 || py >= h) {
                        continue;
                    }
                    for (int sx = 0; sx < scale; sx++) {
                        int pxx = x + col * scale + sx;
                        if (pxx >= 0 && pxx < w) {
                            px[py * w + pxx] = colour;
                        }
                    }
                }
            }
        }
    }

    // A 5x7 bitmap font, one int per row, only the characters the overlay can produce. Embedding
    // it keeps the generator self-contained — no font asset, no render pass, no GL.
    private static final int GLYPH_W = 5;
    private static final int GLYPH_H = 7;
    private static final String GLYPH_ORDER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -.:X/()";
    private static final int[] GLYPHS = {
            0x04,0x0A,0x11,0x11,0x1F,0x11,0x11,   // A
            0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E,   // B
            0x0E,0x11,0x10,0x10,0x10,0x11,0x0E,   // C
            0x1E,0x11,0x11,0x11,0x11,0x11,0x1E,   // D
            0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F,   // E
            0x1F,0x10,0x10,0x1E,0x10,0x10,0x10,   // F
            0x0E,0x11,0x10,0x17,0x11,0x11,0x0E,   // G
            0x11,0x11,0x11,0x1F,0x11,0x11,0x11,   // H
            0x1F,0x04,0x04,0x04,0x04,0x04,0x1F,   // I
            0x07,0x02,0x02,0x02,0x02,0x12,0x0C,   // J
            0x11,0x12,0x14,0x18,0x14,0x12,0x11,   // K
            0x10,0x10,0x10,0x10,0x10,0x10,0x1F,   // L
            0x11,0x1B,0x15,0x15,0x11,0x11,0x11,   // M
            0x11,0x19,0x15,0x13,0x11,0x11,0x11,   // N
            0x0E,0x11,0x11,0x11,0x11,0x11,0x0E,   // O
            0x1E,0x11,0x11,0x1E,0x10,0x10,0x10,   // P
            0x0E,0x11,0x11,0x11,0x15,0x12,0x0D,   // Q
            0x1E,0x11,0x11,0x1E,0x14,0x12,0x11,   // R
            0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E,   // S
            0x1F,0x04,0x04,0x04,0x04,0x04,0x04,   // T
            0x11,0x11,0x11,0x11,0x11,0x11,0x0E,   // U
            0x11,0x11,0x11,0x11,0x11,0x0A,0x04,   // V
            0x11,0x11,0x11,0x15,0x15,0x1B,0x11,   // W
            0x11,0x11,0x0A,0x04,0x0A,0x11,0x11,   // X
            0x11,0x11,0x0A,0x04,0x04,0x04,0x04,   // Y
            0x1F,0x01,0x02,0x04,0x08,0x10,0x1F,   // Z
            0x0E,0x11,0x13,0x15,0x19,0x11,0x0E,   // 0
            0x04,0x0C,0x04,0x04,0x04,0x04,0x0E,   // 1
            0x0E,0x11,0x01,0x02,0x04,0x08,0x1F,   // 2
            0x1F,0x02,0x04,0x02,0x01,0x11,0x0E,   // 3
            0x02,0x06,0x0A,0x12,0x1F,0x02,0x02,   // 4
            0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E,   // 5
            0x06,0x08,0x10,0x1E,0x11,0x11,0x0E,   // 6
            0x1F,0x01,0x02,0x04,0x08,0x08,0x08,   // 7
            0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E,   // 8
            0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C,   // 9
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,   // space
            0x00,0x00,0x00,0x1F,0x00,0x00,0x00,   // -
            0x00,0x00,0x00,0x00,0x00,0x0C,0x0C,   // .
            0x00,0x0C,0x0C,0x00,0x0C,0x0C,0x00,   // :
            0x11,0x11,0x0A,0x04,0x0A,0x11,0x11,   // X (duplicate slot, kept for the WxH label)
            0x01,0x02,0x02,0x04,0x08,0x08,0x10,   // /
            0x02,0x04,0x08,0x08,0x08,0x04,0x02,   // (
            0x08,0x04,0x02,0x02,0x02,0x04,0x08,   // )
    };
}
