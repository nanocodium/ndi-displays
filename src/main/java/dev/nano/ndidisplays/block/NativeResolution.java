package dev.nano.ndidisplays.block;

/**
 * Works out a screen's native pixel count — the resolution a source has to be for one video
 * pixel to land on one LED, with no scaling.
 *
 * Every screen in this mod is built from a physical size and a pixel pitch, so its real
 * resolution is emergent: it depends on how many panels were placed, how big a disc was dialled
 * in, how far a curve wraps. That leaves an operator guessing what to set Resolume or a browser
 * to, and guessing wrong means a stretched or resampled picture on a wall that is perfectly
 * capable of showing it 1:1. This turns that into a number they can read off the screen itself.
 *
 * The formulas deliberately mirror what each renderer feeds its shader as the LED grid, because
 * that grid <em>is</em> the screen's resolution. If a renderer's geometry changes, this has to
 * change with it or the advice becomes a lie.
 */
public final class NativeResolution {

    /**
     * Largest frame worth asking a single source to produce. 4K is already about 500 Mbit/s of
     * SpeedHQ on the wire; past it NDI, encoders and GPU surfaces all start refusing, so a screen
     * whose native size exceeds this cannot be driven 1:1 by one feed however it is configured.
     */
    public static final int MAX_FEED_WIDTH = 3840;
    public static final int MAX_FEED_HEIGHT = 2160;

    /**
     * Pitches the GUIs offer, coarsest last. Advice is snapped to these rather than to an
     * arbitrary number, because it has to be a value the operator can actually select.
     */
    private static final int[] PITCHES = {512, 384, 256, 208, 170, 128, 96, 64, 48, 32};

    /**
     * @param width        native pixels across
     * @param height       native pixels down
     * @param sourceWidth  pixels a source needs to be, once the input window is accounted for
     * @param sourceHeight ditto
     * @param cropped      whether the input window is smaller than the full frame
     * @param pitch        pixels per block this was measured at, so the advice can rescale it
     */
    public record Native(int width, int height, int sourceWidth, int sourceHeight, boolean cropped,
                         int pitch) {

        /** Whether one NDI feed can carry this screen at its native resolution. */
        public boolean fitsOneFeed() {
            return sourceWidth <= MAX_FEED_WIDTH && sourceHeight <= MAX_FEED_HEIGHT;
        }

        /**
         * The coarsest-detail-preserving pitch that brings this screen inside one feed — the
         * largest listed pitch whose resulting resolution still fits, so the operator loses as
         * little pixel density as the limit allows.
         *
         * @return the pitch to select, or the current one when it already fits
         */
        public int suggestedPitch() {
            if (fitsOneFeed() || pitch <= 0) {
                return pitch;
            }
            for (int candidate : PITCHES) {
                if (candidate > pitch) {
                    continue;              // never suggest going finer; that makes it worse
                }
                if (atPitch(candidate).fitsOneFeed()) {
                    return candidate;
                }
            }
            return PITCHES[PITCHES.length - 1];
        }

        /**
         * This screen's resolution if its pitch were {@code newPitch}. The screen's physical size
         * is native divided by pitch, so scaling one scales the other exactly.
         */
        public Native atPitch(int newPitch) {
            if (pitch <= 0) {
                return this;
            }
            float k = newPitch / (float) pitch;
            return new Native(Math.max(1, Math.round(width * k)),
                    Math.max(1, Math.round(height * k)),
                    Math.max(1, Math.round(sourceWidth * k)),
                    Math.max(1, Math.round(sourceHeight * k)),
                    cropped, newPitch);
        }

        /**
         * What to actually set the source to: the native size when it fits, otherwise the size at
         * the suggested pitch. Rounded to even numbers, because video encoders and NDI want even
         * dimensions and one pixel is invisible.
         */
        public String recommendedSource() {
            Native target = fitsOneFeed() ? this : atPitch(suggestedPitch());
            return even(target.sourceWidth()) + " x " + even(target.sourceHeight());
        }

        private static int even(int v) {
            return v % 2 == 0 ? v : v - 1;
        }

        /** e.g. {@code "3840 x 2160 (16:9)"}. */
        public String describe() {
            return width + " x " + height + " (" + aspect(width, height) + ")";
        }

        /** What to set the source to; differs from the native size only when cropping. */
        public String describeSource() {
            return sourceWidth + " x " + sourceHeight;
        }
    }

    /** Curved screens are a slab; the video sits on one face, half a thickness off centre. */
    private static final float CURVED_THICKNESS = 0.12F;

    private NativeResolution() {
    }

    /**
     * A flat LED wall: pixels per cabinet times the number of cabinets.
     *
     * Diagonal walls are not a special case. A 45° cabinet is physically √2 wider but carries the
     * same LED count, exactly as a real cabinet does — so the pixel grid is unchanged and only
     * the pitch gets coarser.
     */
    public static Native of(LedPanelBlockEntity be) {
        WallScanner.WallInfo wall = be.getWallInfo();
        int panelsW = wall == null ? 1 : wall.width();
        int panelsH = wall == null ? 1 : wall.height();
        int px = be.getPixelsPerBlock();
        return withCrop(px * panelsW, px * panelsH, be.crop(), px);
    }

    /** A video disc: square, since the circle is inscribed in its own bounding box. */
    public static Native of(RoundScreenBlockEntity be) {
        int side = Math.round(be.getPixelsPerBlock() * be.getRadius() * 2.0F);
        return withCrop(side, side, be.crop(), be.getPixelsPerBlock());
    }

    /**
     * A curved screen: the arc length along the video face, not the chord, since that is the
     * distance the pixels actually have to cover. Video repeat divides it, because each copy is a
     * full frame.
     */
    public static Native of(CurvedScreenBlockEntity be) {
        float radius = be.getRadius();
        float faceRadius = be.isConvex()
                ? radius + CURVED_THICKNESS * 0.5F
                : radius - CURVED_THICKNESS * 0.5F;
        float arc = (float) Math.toRadians(be.getArcAngle());
        int repeat = Math.max(1, be.getVideoRepeat());
        int w = Math.round(be.getPixelsPerBlock() * arc * faceRadius / repeat);
        int h = Math.round(be.getPixelsPerBlock() * be.getScreenHeight());
        return withCrop(Math.max(1, w), Math.max(1, h), be.crop(), be.getPixelsPerBlock());
    }

    /**
     * One flown tile, and the whole canvas it belongs to.
     *
     * The canvas is the number that matters: a bank of winches shows one shared frame, so the
     * source has to be sized for the full grid, not for a single tile.
     */
    public static Native ofTile(KineticWinchBlockEntity be) {
        int px = be.getPixelsPerBlock();
        return new Native(px * be.getPanelWidth(), px * be.getPanelHeight(),
                px * be.getPanelWidth(), px * be.getPanelHeight(), false, px);
    }

    public static Native ofCanvas(KineticWinchBlockEntity be) {
        int px = be.getPixelsPerBlock();
        int w = px * be.getPanelWidth() * be.getCanvasCols();
        int h = px * be.getPanelHeight() * be.getCanvasRows();
        return new Native(w, h, w, h, false, px);
    }

    /**
     * Scales the native size up by the input window, so the answer stays "what should the source
     * be". Cropping to a quarter of the frame means the source needs to be twice as big in each
     * axis to still deliver one pixel per LED.
     */
    private static Native withCrop(int width, int height, CropWindow crop, int pitch) {
        if (crop == null || crop.isFull()) {
            return new Native(width, height, width, height, false, pitch);
        }
        float du = Math.max(0.01F, crop.du());
        float dv = Math.max(0.01F, crop.dv());
        return new Native(width, height,
                Math.round(width / du), Math.round(height / dv), true, pitch);
    }

    /** Aspect as a reduced ratio, falling back to a decimal when it is not a tidy one. */
    public static String aspect(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "-";
        }
        int g = gcd(width, height);
        int aw = width / g;
        int ah = height / g;
        if (aw <= 64 && ah <= 64) {
            return aw + ":" + ah;
        }
        return String.format("%.2f:1", width / (float) height);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    /**
     * How an incoming stream compares to what the screen wants — the line that tells an operator
     * whether they are actually seeing native pixels or a resample.
     *
     * @return a human-readable verdict, or null when nothing is arriving to compare
     */
    public static String compare(Native target, int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }
        int wantW = target.sourceWidth();
        int wantH = target.sourceHeight();
        if (sourceWidth == wantW && sourceHeight == wantH) {
            return "1:1 — native";
        }
        float sx = sourceWidth / (float) wantW;
        float sy = sourceHeight / (float) wantH;
        String scale = Math.abs(sx - sy) < 0.02F
                ? String.format("%.2fx", sx)
                : String.format("%.2fx by %.2fx — stretched", sx, sy);
        String direction = sx * sy > 1.0F ? "downscaled" : "upscaled";
        return sourceWidth + " x " + sourceHeight + " -> " + scale + " " + direction;
    }
}
