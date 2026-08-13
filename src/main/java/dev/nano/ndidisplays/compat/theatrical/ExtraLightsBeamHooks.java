package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a flown fixture's beam through Theatrical Extra Lights' volumetric pipeline, so a
 * fixture hung from a winch gets the same raymarched shaft of light as one bolted to a truss
 * — rather than the flat four-quad flare that is all base Theatrical offers.
 *
 * Reached entirely by reflection. Extra Lights is not a compile dependency (it is a third
 * optional mod after Theatrical and Shimmer), and resolving it lazily means a missing or
 * renamed class degrades to the classic cone instead of breaking rendering.
 *
 * The contract mirrors Extra Lights' own {@code ExtraLightsFixtureRenderer}:
 * <ul>
 *   <li>{@code BeamRenderData} is built in <em>world space</em> — origin and direction, not a
 *       local transform.</li>
 *   <li>{@code VolumetricBeamRenderer.render(data, pose)} is handed a <em>fresh identity</em>
 *       PoseStack, not the caller's. It queues itself onto Theatrical's LazyRenderers, which
 *       is what sorts beams far-to-near for correct translucency.</li>
 *   <li>One renderer instance is kept per fixture and reused across frames, as Extra Lights
 *       does with its per-block-entity map.</li>
 * </ul>
 */
final class ExtraLightsBeamHooks {

    private static final String PKG = "com.github.dumann089.theatricalextralights";

    /**
     * Texture the beam is drawn with. This is <em>not</em> optional: Extra Lights feeds
     * {@code goboTexture} straight into the render type's TextureStateShard, so a null there
     * throws while the render type is being built and the beam never draws. Its own fixtures
     * pass this same "open" gobo plate when no gobo is inserted, which is the no-gobo case a
     * flown fixture is always in.
     */
    private static final net.minecraft.resources.ResourceLocation OPEN_GOBO =
            new net.minecraft.resources.ResourceLocation(
                    "theatricalextralights", "textures/gobos/generic_1/open.png");

    /**
     * Beam cone half-angle range, degrees, lerped by zoom — the same shape Extra Lights uses
     * for its own fixtures ({@code tan(toRadians(min + zoom * (max - min)))}). Numbers chosen
     * to read like a moving head: a tight 5 degrees at zoom 0 opening to 25 at full.
     *
     * Getting this wrong is what made the beam invisible rather than merely wrong: an earlier
     * version derived the tangent directly from beam width and focus, which for an unpatched
     * fixture (focus 0) collapsed to 0.01 — tan(0.57 degrees), a laser too thin to see.
     */
    private static final float BEAM_MIN_HALF_DEG = 5.0F;
    private static final float BEAM_MAX_HALF_DEG = 25.0F;

    /** Escape hatch: {@code -Dndidisplays.volumetricBeam=false} forces the classic cone. */
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("ndidisplays.volumetricBeam", "true"));

    private enum State { UNKNOWN, READY, ABSENT }

    private static State state = State.UNKNOWN;
    private static Constructor<?> dataCtor;
    private static Method renderMethod;
    private static Method enabledMethod;
    private static Class<?> rendererClass;

    /**
     * One reused renderer per fixture position. Bounded rather than weak: the key is a
     * BlockPos, so nothing holds a block entity alive, and a stage never has enough winches
     * for the cap to matter.
     */
    private static final Map<BlockPos, Object> RENDERERS = new ConcurrentHashMap<>();
    private static final int MAX_RENDERERS = 256;

    private ExtraLightsBeamHooks() {
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static boolean loggedDisabled;
    private static boolean loggedSubmit;
    /**
     * Consecutive submit failures. Latching off after the first one meant a single transient
     * failure — a resource reload, a frame where a shader was mid-swap — silently disabled the
     * volumetric beam for the whole session and looked exactly like the feature not existing.
     */
    private static java.lang.reflect.Field beamCountField;
    private static boolean loggedNoGeometry;
    private static int failures;
    private static final int MAX_FAILURES = 3;

    private static synchronized boolean resolve() {
        if (state != State.UNKNOWN) {
            return state == State.READY;
        }
        try {
            Class<?> dataClass = Class.forName(PKG + ".client.render.beam.BeamRenderData");
            rendererClass = Class.forName(PKG + ".client.render.beam.VolumetricBeamRenderer");
            // The 16-argument form, the one Extra Lights itself calls (the 15-arg overload
            // omits baseRadius). Matched on parameter count so a reordered signature fails
            // resolution rather than silently mismatching argument meanings.
            Constructor<?> found = null;
            for (Constructor<?> c : dataClass.getConstructors()) {
                if (c.getParameterCount() == 16) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                state = State.ABSENT;
                return false;
            }
            dataCtor = found;
            renderMethod = rendererClass.getMethod("render", dataClass, PoseStack.class);
            try {
                beamCountField = rendererClass.getDeclaredField("activeBeamCount");
                beamCountField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException noField) {
                // Without it we cannot confirm geometry was built; better to decline the
                // volumetric path than to risk suppressing the fallback for nothing.
                beamCountField = null;
            }
            try {
                enabledMethod = Class.forName(PKG + ".config.TheatricalExtraLightsConfig")
                        .getMethod("isVolumetricBeamEnabled");
            } catch (ReflectiveOperationException noConfig) {
                // Config gate is optional; without it assume the user wants volumetric.
                enabledMethod = null;
            }
            state = State.READY;
            LOGGER.info("[ndidisplays] Extra Lights volumetric beam API resolved"
                    + " (config gate {})", enabledMethod != null ? "present" : "absent");
            return true;
        } catch (ReflectiveOperationException | LinkageError absent) {
            state = State.ABSENT;
            LOGGER.info("[ndidisplays] Extra Lights volumetric beam unavailable ({});"
                    + " flown fixtures will use the classic beam", absent.toString());
            return false;
        }
    }

    /** Whether Extra Lights is present and its volumetric beam is switched on. */
    static boolean available() {
        if (!resolve()) {
            return false;
        }
        if (enabledMethod == null) {
            return true;
        }
        try {
            boolean on = Boolean.TRUE.equals(enabledMethod.invoke(null));
            if (!on && !loggedDisabled) {
                loggedDisabled = true;
                LOGGER.info("[ndidisplays] Extra Lights reports its volumetric beam is"
                        + " switched off; flown fixtures will use the classic beam");
            }
            return on;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // RuntimeException included deliberately: this runs *outside* submit()'s try, so an
            // unchecked throw here would escape into the block entity renderer rather than
            // falling back to the classic beam.
            state = State.ABSENT;
            LOGGER.warn("[ndidisplays] Extra Lights volumetric config gate failed: {}", e.toString(), e);
            return false;
        }
    }

    /**
     * Queues one volumetric beam for this frame.
     *
     * @param headMatrix the head transform in block-local space, beam start included — its
     *                   translation is the lens, and the beam travels along its -Z, matching
     *                   the geometry Theatrical's own cone uses
     * @return true when the beam was submitted
     */
    static boolean submit(BlockPos fixturePos, Matrix4f headMatrix, float beamWidth,
                          float focus01, float r, float g, float b,
                          float intensity01, float length) {
        if (!ENABLED || !available()) {
            return false;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        try {
            // Origin is FIXTURE-LOCAL, not world. Extra Lights builds the beam geometry from
            // this origin, then its lazy pass translates the whole thing by
            // (fixturePos - cameraPos) before drawing. Passing absolute world coordinates
            // therefore counts the position twice and throws the beam roughly twice as far
            // from the camera as the fixture — it renders, just nowhere near the light.
            //
            // The direction and basis vectors below are orientation only, so they are the same
            // either way.
            Vec3 origin = new Vec3(headMatrix.m30(), headMatrix.m31(), headMatrix.m32());
            Vec3 axisU = norm(headMatrix.m00(), headMatrix.m01(), headMatrix.m02());
            Vec3 axisV = norm(headMatrix.m10(), headMatrix.m11(), headMatrix.m12());
            Vec3 dir = norm(-headMatrix.m20(), -headMatrix.m21(), -headMatrix.m22());

            // Cone spread, as a real half-angle in degrees like Extra Lights computes for its
            // own fixtures, not derived from beam width. Zoom widens the cone.
            float zoom = Math.max(0.0F, Math.min(1.0F, focus01));
            float halfDeg = BEAM_MIN_HALF_DEG + zoom * (BEAM_MAX_HALF_DEG - BEAM_MIN_HALF_DEG);
            float tanHalf = (float) Math.tan(Math.toRadians(halfDeg));
            int colour = (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);

            Object data = dataCtor.newInstance(
                    fixturePos,
                    origin,
                    dir,
                    axisU,
                    axisV,
                    zoom,                                     // zoomNorm
                    length,                                   // scanLen
                    tanHalf,                                  // tanHalfAngle
                    colour,
                    Math.max(0.0F, Math.min(1.0F, intensity01)),
                    OPEN_GOBO,                                // goboTexture — see below
                    0.0F,                                     // goboRotation
                    level,
                    1.0F,                                     // widthScale
                    1.0F,                                     // heightScale
                    Math.max(0.02F, beamWidth));              // baseRadius

            if (RENDERERS.size() > MAX_RENDERERS) {
                RENDERERS.clear();
            }
            Object renderer = RENDERERS.computeIfAbsent(fixturePos.immutable(), pos -> {
                try {
                    return rendererClass.getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            });
            if (renderer == null) {
                return false;
            }
            // A fresh identity stack: the data is already world-space, and Extra Lights
            // re-anchors camera-relatively inside its own lazy render.
            renderMethod.invoke(renderer, data, new PoseStack());

            // Verify rather than assume. render() returns void and silently does nothing when
            // its own guards reject the beam (volumetric switched off, intensity not above
            // zero), and its lazy pass then returns early because activeBeamCount is still 0.
            // Reading that counter is the only way to know a beam was really built — without
            // it we would report success and suppress the fallback cone, leaving no beam at
            // all, which is exactly what happened before this check existed.
            if (beamCountField != null && beamCountField.getInt(renderer) <= 0) {
                if (!loggedNoGeometry) {
                    loggedNoGeometry = true;
                    LOGGER.warn("[ndidisplays] Extra Lights accepted the beam but built no"
                            + " geometry (activeBeamCount 0); using the classic beam");
                }
                return false;
            }
            failures = 0;
            if (!loggedSubmit) {
                loggedSubmit = true;
                LOGGER.info("[ndidisplays] flown fixture beam submitted to Extra Lights'"
                        + " volumetric renderer");
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // Log the real cause before giving up — a silent fallback here is indistinguishable
            // from Extra Lights simply not being installed, which makes it undebuggable.
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : e;
            if (++failures >= MAX_FAILURES) {
                state = State.ABSENT;
            }
            LOGGER.warn("[ndidisplays] Extra Lights volumetric beam failed ({}/{}), falling back"
                    + " to the classic beam: {}", failures, MAX_FAILURES, cause.toString(), cause);
            return false;
        }
    }

    private static Vec3 norm(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        return len < 1.0e-6 ? new Vec3(0.0, -1.0, 0.0) : new Vec3(x / len, y / len, z / len);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0F)));
    }
}
