package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.imabad.theatrical.client.LazyRenderers;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Moves the beams of a flown fixture off the block grid and onto the truss.
 *
 * Theatrical draws a fixture's beam (and the lens glow in front of it) from a lazy
 * renderer that anchors itself on {@code blockEntity.getBlockPos()} camera-relatively,
 * ignoring whatever pose the body was drawn with. A ghost fixture on a flying rig lives
 * in a whole-block cell, so its body glides with the entity while its beam sits on the
 * grid, jumping a block at a time and trailing the head in between: the beams look as
 * if they are stuttering while the winch runs. Every renderer queued while the ghost was
 * drawing is wrapped here so it renders shifted by the body's sub-block offset.
 *
 * Only classloaded behind {@link HoistFixtureCompat}'s presence check.
 */
final class HoistBeamHooks {

    private static final Field RENDERERS;

    static {
        Field f;
        try {
            f = LazyRenderers.class.getDeclaredField("renderers");
            f.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            f = null;
        }
        RENDERERS = f;
    }

    private HoistBeamHooks() {
    }

    static boolean available() {
        return RENDERERS != null;
    }

    @SuppressWarnings("unchecked")
    private static List<LazyRenderers.LazyRenderer> queue() throws IllegalAccessException {
        return (List<LazyRenderers.LazyRenderer>) RENDERERS.get(null);
    }

    /** Number of beams queued so far this frame; pass to {@link #shiftSince}. */
    static int mark() throws IllegalAccessException {
        return queue().size();
    }

    /** Re-anchors every lazy renderer added after {@code mark} by {@code delta} blocks. */
    static void shiftSince(int mark, Vec3 delta) throws IllegalAccessException {
        List<LazyRenderers.LazyRenderer> queue = queue();
        for (int i = Math.max(0, mark); i < queue.size(); i++) {
            queue.set(i, new Shifted(queue.get(i), delta));
        }
    }

    private static final class Shifted extends LazyRenderers.LazyRenderer {
        private final LazyRenderers.LazyRenderer inner;
        private final Vec3 delta;

        Shifted(LazyRenderers.LazyRenderer inner, Vec3 delta) {
            this.inner = inner;
            this.delta = delta;
        }

        @Override
        public void render(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack,
                           Camera camera, float partialTick) {
            poseStack.pushPose();
            poseStack.translate(delta.x, delta.y, delta.z);
            inner.render(bufferSource, poseStack, camera, partialTick);
            poseStack.popPose();
        }

        @Override
        public Vec3 getPos(float partialTick) {
            return inner.getPos(partialTick).add(delta);
        }
    }
}
