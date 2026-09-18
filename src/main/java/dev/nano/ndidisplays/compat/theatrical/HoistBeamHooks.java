package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.imabad.theatrical.blockentities.light.BaseLightBlockEntity;
import dev.imabad.theatrical.client.LazyRenderers;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

    /**
     * Sets a ghost fixture's beam length to the distance from where its body is drawn
     * this frame to the first surface along its optical axis.
     *
     * Theatrical's own {@code doRayTrace} measures from the fixture's block cell to the
     * block the beam hits: a whole-block figure, which is fine for a fixture bolted to a
     * truss and steps a block at a time on one that is flying. Extra Lights puts its gobo
     * projection at exactly that length, so the floor pattern would lift off the stage
     * and snap back once per block of travel. Measuring from the real position, every
     * frame, keeps it on the floor. Ghosts never tick, so nothing overwrites this.
     */
    static void aimBeam(BlockEntity ghost, Vec3 drawnCentre) {
        if (!(ghost instanceof BaseLightBlockEntity light) || light.getIntensity() <= 0) {
            return;
        }
        Level level = ghost.getLevel();
        Player player = Minecraft.getInstance().player;
        if (level == null || player == null) {
            return;
        }
        Vec3 dir = BaseLightBlockEntity.rayTraceDir(light);
        if (dir.lengthSqr() < 1.0e-6) {
            return;
        }
        double max = Math.max(1.0, light.getMaxLightDistance());
        BlockHitResult hit = level.clip(new ClipContext(drawnCentre,
                drawnCentre.add(dir.normalize().scale(max)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double distance = hit.getType() == HitResult.Type.MISS
                ? max : hit.getLocation().distanceTo(drawnCentre);
        if (Math.abs(distance - light.getDistance()) < 0.01) {
            return;
        }
        CompoundTag tag = ghost.saveWithoutMetadata();
        tag.putDouble("distance", distance);
        ghost.load(tag);
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
