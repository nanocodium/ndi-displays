package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolates Theatrical's deferred beam queue across a camera capture.
 *
 * {@code LazyRenderers} keeps ONE static list for the whole game, drained once per world render.
 * A capture runs a full nested {@code renderLevel}, so every fixture in the rig's view queues its
 * beam onto that same shared list. Anything not drained before the capture ends is then drawn
 * during the player's own frame instead, carrying the state it was queued with — which is how a
 * camera's framebuffer ends up composited over the player's screen (Extra Lights' volumetric
 * shaft is a screen-space pass that samples the main render target, and during a capture that
 * target is the camera's) while the beams themselves go missing from the NDI output. The two
 * symptoms are one bug: a queue that outlives the pass that filled it.
 *
 * Same shape as {@code ShimmerCompat}'s post-chain suppression — save, neutralise, restore —
 * because the underlying hazard is identical: global render state a nested pass must not inherit
 * or leak.
 *
 * All access is reflective and every failure is swallowed, so this class is safe to load with no
 * Theatrical present and can never take a capture down.
 */
public final class TheatricalLazyQueue {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Field renderersField;
    private static Method doRenderMethod;
    private static boolean broken;
    private static boolean leftoverLogged;
    private static boolean drainLogged;

    private TheatricalLazyQueue() {
    }

    /**
     * Empties the shared queue and hands back what was in it.
     *
     * @return the previous contents to pass to {@link #restore}, or null when unavailable
     */
    public static List<Object> isolate() {
        List<Object> live = live();
        if (live == null) {
            return null;
        }
        List<Object> saved = new ArrayList<>(live);
        live.clear();
        return saved;
    }

    /**
     * Discards anything the capture queued and puts the player's own beams back.
     *
     * @param saved the list returned by {@link #isolate}
     * @return how many entries the capture left behind — non-zero means those beams were never
     *         drawn into the feed, and without this call they would have been drawn into the
     *         player's frame instead
     */
    public static int restore(List<Object> saved) {
        List<Object> live = live();
        if (live == null || saved == null) {
            return 0;
        }
        int leftover = live.size();
        live.clear();
        live.addAll(saved);
        if (leftover > 0 && !leftoverLogged) {
            leftoverLogged = true;
            LOGGER.info("[ndidisplays] {} fixture beam(s) were queued during a camera capture but"
                    + " never drawn into it; discarded rather than leaked into the player's frame."
                    + " Beams will be absent from that feed.", leftover);
        }
        return leftover;
    }

    /**
     * Draws everything currently queued, here and now.
     *
     * Theatrical drains the queue from a mixin placed after the tripwire layer, and a capture
     * never reaches it: {@code transparencyChain} has to be nulled so the Fabulous targets do not
     * break off-screen rendering, and that sends {@code renderLevel} down its other branch, past
     * the injection point. The beams therefore stayed queued — missing from the feed, and left to
     * be drawn in the player's own frame instead. Calling the drain directly from a render-stage
     * event puts them back in the capture, at the same point in the frame Theatrical intended.
     *
     * @return how many were drawn, or -1 if the drain is unavailable
     */
    public static int drain(Camera camera, PoseStack poseStack,
                            MultiBufferSource.BufferSource bufferSource, float partialTick) {
        List<Object> live = live();
        if (live == null || live.isEmpty()) {
            return 0;
        }
        int queued = live.size();
        try {
            if (doRenderMethod == null) {
                Class<?> lazy = Class.forName("dev.imabad.theatrical.client.LazyRenderers");
                doRenderMethod = lazy.getMethod("doRender", Camera.class, PoseStack.class,
                        MultiBufferSource.BufferSource.class, float.class);
            }
            doRenderMethod.invoke(null, camera, poseStack, bufferSource, partialTick);
            // Flush now: this runs mid-frame inside a capture, and the beams must land in the
            // capture target rather than in whatever batch happens to be flushed after it.
            bufferSource.endBatch();
            if (!drainLogged) {
                drainLogged = true;
                LOGGER.info("[ndidisplays] drawing {} fixture beam(s) into camera captures"
                        + " (Theatrical's own drain is unreachable in an off-screen pass)", queued);
            }
            return queued;
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[ndidisplays] could not draw fixture beams into the capture ({});"
                    + " feeds will show fixtures without beams", t.toString());
            return -1;
        }
    }

    /** The live static queue, or null if Theatrical is absent or its shape changed. */
    @SuppressWarnings("unchecked")
    private static List<Object> live() {
        if (broken) {
            return null;
        }
        try {
            if (renderersField == null) {
                Class<?> lazy = Class.forName("dev.imabad.theatrical.client.LazyRenderers");
                renderersField = lazy.getDeclaredField("renderers");
                renderersField.setAccessible(true);
            }
            // The field is final, so the list is mutated in place rather than replaced.
            return (List<Object>) renderersField.get(null);
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[ndidisplays] cannot isolate Theatrical's beam queue across captures ({});"
                    + " fixture beams may flicker onto the screen during capture", t.toString());
            return null;
        }
    }
}
