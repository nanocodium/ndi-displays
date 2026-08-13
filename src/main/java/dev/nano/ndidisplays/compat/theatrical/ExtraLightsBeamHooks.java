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
        if (!available()) {
            return false;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        try {
            // World-space frame of the head. JOML stores translation in m30..m32 and the
            // basis vectors in the first three columns.
            Vec3 origin = new Vec3(
                    fixturePos.getX() + headMatrix.m30(),
                    fixturePos.getY() + headMatrix.m31(),
                    fixturePos.getZ() + headMatrix.m32());
            Vec3 axisU = norm(headMatrix.m00(), headMatrix.m01(), headMatrix.m02());
            Vec3 axisV = norm(headMatrix.m10(), headMatrix.m11(), headMatrix.m12());
            Vec3 dir = norm(-headMatrix.m20(), -headMatrix.m21(), -headMatrix.m22());

            // Cone spread. Theatrical's flare grows the half-width from beamWidth at the lens
            // to beamWidth * (1 + focus*255*len*0.03) at the far end, so the tangent of the
            // half-angle is beamWidth * focus*255 * 0.03 — length cancels. Reusing that keeps
            // a flown fixture's spread identical whichever beam style is drawing it.
            float tanHalf = Math.max(0.01F, beamWidth * focus01 * 255.0F * 0.03F);
            int colour = (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);

            Object data = dataCtor.newInstance(
                    fixturePos,
                    origin,
                    dir,
                    axisU,
                    axisV,
                    Math.max(0.0F, Math.min(1.0F, focus01)),  // zoomNorm
                    length,                                   // scanLen
                    tanHalf,                                  // tanHalfAngle
                    colour,
                    Math.max(0.0F, Math.min(1.0F, intensity01)),
                    null,                                     // goboTexture — none flown
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
            state = State.ABSENT;
            LOGGER.warn("[ndidisplays] Extra Lights volumetric beam failed, falling back to the"
                    + " classic beam: {}", cause.toString(), cause);
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
