package dev.nano.ndidisplays.client.render;

import com.lowdragmc.shimmer.client.light.ColorPointLight;
import com.lowdragmc.shimmer.client.light.LightManager;
import dev.nano.ndidisplays.block.CropWindow;
import dev.nano.ndidisplays.block.PanelFacing;
import dev.nano.ndidisplays.block.WallScanner;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Content-coloured light thrown by the video screens, through Shimmer.
 *
 * A real LED wall is a light source, not a picture hanging on one: it washes everything in front
 * of it with whatever it is showing, which is why a video wall changes the look of a stage on
 * every cut. Until now our screens glowed (Shimmer bloom) but lit nothing, so a wall full of deep
 * blue left the floor in front of it lit by torches.
 *
 * Large screens get a grid of lights rather than one, each carrying the mean colour of the part of
 * the frame directly behind it. That is the whole point of the effect: a wall showing red on the
 * left and blue on the right must light the floor red on the left and blue on the right, which a
 * single averaged light can never do — it would just wash everything magenta.
 *
 * Renderer-driven like {@link ShimmerSphereLights}: every screen refreshes its lights on the
 * frames it draws, and {@link #tick()} drops anything that stopped being refreshed (screen turned
 * off, block broken, chunk unloaded, dimension left). Touches Shimmer types directly, so it must
 * only be classloaded behind {@link LedWallRenderer#SHIMMER_LOADED}.
 */
public final class ScreenLights {

    /** Ticks without a render refresh before a screen's lights are dropped. */
    private static final int EXPIRE_TICKS = 5;

    /**
     * How often the frame is re-sampled. The picture changes every frame, but the light it throws
     * does not need to: sampling at ~16 Hz and easing between samples costs a fraction of the work
     * and looks steadier than chasing per-frame noise.
     */
    private static final long SAMPLE_INTERVAL_MS = 60L;

    /** Fraction of the way to the new sample each update — the ease that kills flicker. */
    private static final float SMOOTHING = 0.5F;

    /** Roughly one light per this many blocks of screen, so a light covers what it can reach. */
    private static final double BLOCKS_PER_LIGHT = 7.0;

    /** Ceilings. Shimmer's light budget is shared with fixtures and spheres, so screens yield. */
    private static final int MAX_COLS = 4;
    private static final int MAX_ROWS = 2;
    private static final int MAX_TOTAL_LIGHTS = 40;

    /** Blocks in front of the screen face to sit, clear of the panel's own geometry. */
    private static final double PUSH_OUT = 0.6;

    /** Below this the screen is treated as dark and its lights are disabled outright. */
    private static final float DARK_CUTOFF = 0.02F;

    /**
     * How much light a screen throws relative to its own picture brightness.
     *
     * A wall is a large, diffuse, fairly dim emitter — nothing like the point lamp a Shimmer light
     * models. Driving one at full picture value floods a room: a white screen would light the
     * street like a floodlight. Well under 1 by intent.
     */
    private static final float AREA_GAIN = 0.45F;

    /**
     * Mean colour of each built-in test pattern, so a screen on bars or a colour field lights the
     * room correctly without a video signal. Indexed by the shader's mode: 0 video (sampled, never
     * read from here), 1 bars, 2 alignment grid, 3 white, 4 red, 5 green, 6 blue, 7 checker.
     */
    private static final float[][] PATTERN_COLOUR = {
            {0.0F, 0.0F, 0.0F},
            {0.40F, 0.40F, 0.40F},
            {0.12F, 0.12F, 0.12F},
            {1.0F, 1.0F, 1.0F},
            {1.0F, 0.0F, 0.0F},
            {0.0F, 1.0F, 0.0F},
            {0.0F, 0.0F, 1.0F},
            {0.5F, 0.5F, 0.5F},
    };

    private static final Map<BlockPos, Screen> SCREENS = new HashMap<>();
    private static int tickCounter;
    private static int activeLights;

    /** Scratch, reused every call: this runs inside the render loop. */
    private static float[] rects = new float[MAX_COLS * MAX_ROWS * 4];
    private static float[] sampled = new float[MAX_COLS * MAX_ROWS * 3];

    private ScreenLights() {
    }

    /**
     * A flat LED wall. Its face runs along the cabinet row and up; u follows the row in the same
     * direction the renderer lays it down, and v is inverted because the video's first row is at
     * the top of the wall.
     */
    public static void updateWall(BlockPos owner, WallScanner.WallInfo wall, String source,
                                  int mode, CropWindow crop, float brightness) {
        PanelFacing facing = wall.facing();
        int w = wall.width();
        int h = wall.height();
        Vec3 normal = facing.normal();
        Vec3 right = facing.rightUnit();
        double pitch = facing.pitch();

        int cols = lightCount(pitch * w, MAX_COLS);
        int rows = lightCount(h, MAX_ROWS);

        // Out past the cabinet face; the exact surface offset does not matter once pushed clear.
        Vec3 base = new Vec3(0.5, 0.0, 0.5)
                .subtract(right.scale(pitch * 0.5))
                .add(normal.scale(0.5 + PUSH_OUT));
        Vec3 span = right.scale(pitch * w);

        Vec3[] centres = new Vec3[cols * rows];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int k = j * cols + i;
                // j counts from the top to match v, so height is measured back from the top.
                double up = h * (1.0 - (j + 0.5) / rows);
                centres[k] = base.add(span.scale((i + 0.5) / cols)).add(0.0, up, 0.0);
                putRect(k, (float) i / cols, (float) j / rows,
                        (float) (i + 1) / cols, (float) (j + 1) / rows, crop);
            }
        }
        apply(owner, source, mode, centres, cols, rows,
                Math.max(pitch * w / cols, (double) h / rows), brightness);
    }

    /**
     * A circular screen, lit as the square it is inscribed in. u runs opposite to the renderer's
     * right vector, matching {@code u = 0.5 - 0.5*cos} in the disc's own vertex mapping.
     */
    public static void updateDisc(BlockPos owner, Vec3 faceCentre, Vec3 right, Vec3 up,
                                  Vec3 normal, float radius, String source, int mode,
                                  CropWindow crop, float brightness) {
        int cols = lightCount(radius * 2.0, MAX_COLS);
        int rows = lightCount(radius * 2.0, MAX_ROWS);
        Vec3 out = faceCentre.add(normal.scale(PUSH_OUT));

        Vec3[] centres = new Vec3[cols * rows];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int k = j * cols + i;
                double du = 1.0 - 2.0 * (i + 0.5) / cols;   // u grows towards -right
                double dv = 1.0 - 2.0 * (j + 0.5) / rows;   // v grows downwards
                centres[k] = out.add(right.scale(du * radius)).add(up.scale(dv * radius));
                putRect(k, (float) i / cols, (float) j / rows,
                        (float) (i + 1) / cols, (float) (j + 1) / rows, crop);
            }
        }
        apply(owner, source, mode, centres, cols, rows, radius * 2.0 / cols, brightness);
    }

    /**
     * A curved screen. Lights follow the arc rather than a chord, so a wrapped screen lights the
     * room from the shape it actually is, and each one takes its u from the same mapping the
     * renderer uses — including video repeat, so a tiled image throws each copy's colours.
     */
    public static void updateArc(BlockPos owner, Vec3 centre, Vec3 fwd, Vec3 right, double arc,
                                 double faceRadius, float yBottom, float yTop, int repeat,
                                 boolean convex, String source, int mode, CropWindow crop,
                                 float brightness) {
        double arcLength = Math.abs(arc) * faceRadius;
        double height = yTop - yBottom;
        int cols = lightCount(arcLength, MAX_COLS);
        int rows = lightCount(height, MAX_ROWS);

        Vec3[] centres = new Vec3[cols * rows];
        for (int i = 0; i < cols; i++) {
            double t0 = (double) i / cols;
            double t1 = (double) (i + 1) / cols;
            double tm = (t0 + t1) * 0.5;
            double theta = (tm - 0.5) * arc;
            Vec3 dir = fwd.scale(Math.cos(theta)).add(right.scale(Math.sin(theta)));
            // Concave screens face their own centre, so the light goes the other way.
            Vec3 push = convex ? dir : dir.reverse();
            float[] uu = CurvedScreenRenderer.u(t0, t1, repeat, convex);
            float u0 = Math.max(0.0F, Math.min(uu[0], uu[1]));
            float u1 = Math.min(1.0F, Math.max(uu[0], uu[1]));
            if (u1 - u0 < 1.0E-3F) {
                // The span crossed a seam between two copies; take the whole frame instead.
                u0 = 0.0F;
                u1 = 1.0F;
            }
            for (int j = 0; j < rows; j++) {
                int k = j * cols + i;
                double y = yTop - height * (j + 0.5) / rows;
                centres[k] = new Vec3(centre.x + dir.x * faceRadius, y, centre.z + dir.z * faceRadius)
                        .add(push.scale(PUSH_OUT));
                putRect(k, u0, (float) j / rows, u1, (float) (j + 1) / rows, crop);
            }
        }
        apply(owner, source, mode, centres, cols, rows,
                Math.max(arcLength / cols, height / rows), brightness);
    }

    /** Client tick: drop lights for screens that stopped rendering. */
    public static void tick() {
        tickCounter++;
        Iterator<Map.Entry<BlockPos, Screen>> it = SCREENS.entrySet().iterator();
        while (it.hasNext()) {
            Screen screen = it.next().getValue();
            if (tickCounter - screen.lastSeen > EXPIRE_TICKS) {
                release(screen);
                it.remove();
            }
        }
    }

    /** Logout / world close: release everything. */
    public static void clearAll() {
        for (Screen screen : SCREENS.values()) {
            release(screen);
        }
        SCREENS.clear();
        activeLights = 0;
    }

    /** One light per {@link #BLOCKS_PER_LIGHT}, at least one, never more than the cap. */
    private static int lightCount(double blocks, int cap) {
        return Math.max(1, Math.min(cap, (int) Math.round(blocks / BLOCKS_PER_LIGHT)));
    }

    /** Stores one region's sample rectangle, mapping face space through the input window. */
    private static void putRect(int index, float u0, float v0, float u1, float v1, CropWindow crop) {
        int o = index * 4;
        rects[o] = crop.u0() + u0 * crop.du();
        rects[o + 1] = crop.v0() + v0 * crop.dv();
        rects[o + 2] = crop.u0() + u1 * crop.du();
        rects[o + 3] = crop.v0() + v1 * crop.dv();
    }

    /**
     * Creates or refreshes one screen's lights from its sampled colours.
     *
     * @param extent size in blocks of the largest region, which sets how far a light reaches
     */
    private static void apply(BlockPos owner, String source, int mode, Vec3[] centres,
                              int cols, int rows, double extent, float brightness) {
        int count = cols * rows;
        Screen screen = SCREENS.get(owner);
        if (screen != null && screen.lights.length != count) {
            // The wall was resized or reconfigured; rebuild at the new light count.
            release(screen);
            SCREENS.remove(owner);
            screen = null;
        }
        if (screen == null) {
            if (activeLights + count > MAX_TOTAL_LIGHTS) {
                return;
            }
            screen = new Screen(count);
            SCREENS.put(owner, screen);
        }
        screen.lastSeen = tickCounter;

        long now = System.currentTimeMillis();
        if (now - screen.lastSampleMs >= SAMPLE_INTERVAL_MS) {
            screen.lastSampleMs = now;
            sample(screen, source, mode, count);
        }

        double radiusBase = Math.max(4.0, Math.min(10.0, extent * 0.9 + 2.0));

        // Splitting a screen into a grid buys local colour, NOT extra output — the picture emits
        // what it emits however finely it is sampled. Without this an eight-light wall threw eight
        // times the light of a one-light wall showing the same frame, which flooded whole venues
        // magenta and blew out anyone standing near a screen.
        //
        // Divided by sqrt(count) rather than count: these are incoherent sources and Shimmer's
        // falloff means only the nearest few contribute at any given point, so strict 1/N leaves a
        // large wall dimmer than a small one showing the same thing.
        float spread = AREA_GAIN / (float) Math.sqrt(count);
        for (int i = 0; i < count; i++) {
            float r = Math.min(1.0F, screen.colour[i * 3] * brightness * spread);
            float g = Math.min(1.0F, screen.colour[i * 3 + 1] * brightness * spread);
            float b = Math.min(1.0F, screen.colour[i * 3 + 2] * brightness * spread);
            // Reach and the on/off decision follow the PICTURE's brightness, not the per-light
            // share of it: how far a screen throws depends on what it is showing, not on how
            // finely this code chose to sample it. Using the divided value would shrink a large
            // wall's reach purely for having more sample points, and would switch dim content off.
            float lum = Math.min(1.0F, Math.max(screen.colour[i * 3],
                    Math.max(screen.colour[i * 3 + 1], screen.colour[i * 3 + 2])) * brightness);
            Vec3 pos = centres[i];

            ColorPointLight light = screen.lights[i];
            if (light == null) {
                light = LightManager.INSTANCE.addLight(
                        new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
                        packColor(r, g, b), (float) radiusBase);
                if (light == null) {
                    // Shimmer's budget is full this frame; try again on the next one.
                    continue;
                }
                screen.lights[i] = light;
                activeLights++;
            }
            light.setPos((float) pos.x, (float) pos.y, (float) pos.z);
            // A dim picture reaches less far as well as being dimmer, as a real one does.
            light.radius = (float) (radiusBase * (0.45 + 0.55 * lum));
            light.setColor(r, g, b, 1.0F);
            light.setEnable(lum > DARK_CUTOFF);
            light.update();
        }
    }

    /** Eases each region's colour towards what the screen is currently showing there. */
    private static void sample(Screen screen, String source, int mode, int count) {
        boolean got = false;
        if (mode == 0) {
            NdiStream stream = source == null || source.isBlank() ? null : NdiManager.acquire(source);
            if (stream != null) {
                got = stream.sampleRects(rects, count, sampled);
            }
            if (!got) {
                // No signal: a screen showing black throws no light.
                java.util.Arrays.fill(sampled, 0, count * 3, 0.0F);
                got = true;
            }
        } else {
            float[] flat = PATTERN_COLOUR[Math.floorMod(mode, PATTERN_COLOUR.length)];
            for (int i = 0; i < count; i++) {
                sampled[i * 3] = flat[0];
                sampled[i * 3 + 1] = flat[1];
                sampled[i * 3 + 2] = flat[2];
            }
            got = true;
        }
        if (!got) {
            return;
        }
        for (int i = 0; i < count * 3; i++) {
            screen.colour[i] += (sampled[i] - screen.colour[i]) * SMOOTHING;
        }
    }

    private static void release(Screen screen) {
        for (int i = 0; i < screen.lights.length; i++) {
            if (screen.lights[i] != null) {
                screen.lights[i].remove();
                screen.lights[i] = null;
                activeLights--;
            }
        }
    }

    private static int packColor(float r, float g, float b) {
        return 0xFF000000
                | ((int) (Math.min(1.0F, r) * 255) << 16)
                | ((int) (Math.min(1.0F, g) * 255) << 8)
                | (int) (Math.min(1.0F, b) * 255);
    }

    private static final class Screen {
        final ColorPointLight[] lights;
        final float[] colour;
        long lastSampleMs;
        int lastSeen;

        Screen(int count) {
            this.lights = new ColorPointLight[count];
            this.colour = new float[count * 3];
            this.lastSeen = tickCounter;
        }
    }
}
