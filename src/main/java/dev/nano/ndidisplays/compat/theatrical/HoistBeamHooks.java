package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.imabad.theatrical.blockentities.light.BaseLightBlockEntity;
import dev.imabad.theatrical.client.LazyRenderers;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
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
        // Not through load(): read() would reset prevPan/prevTilt and freeze the head's
        // sweep on every frame the truss moves.
        HoistFixtureHooks.setDistance(light, distance);
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

    /**
     * The real camera moved by {@code -delta}, so anything drawn relative to it lands
     * {@code delta} further along in the world. Rotation is rebuilt through the protected
     * setter so the final look/up/left vectors are populated; everything else delegates.
     */
    private static final class ShiftedCamera extends Camera {
        private final Camera real;

        ShiftedCamera(Camera real, Vec3 delta) {
            this.real = real;
            setRotation(real.getYRot(), real.getXRot());
            setPosition(real.getPosition().subtract(delta));
        }

        @Override
        public net.minecraft.world.entity.Entity getEntity() {
            return real.getEntity();
        }

        @Override
        public boolean isInitialized() {
            return real.isInitialized();
        }

        @Override
        public boolean isDetached() {
            return real.isDetached();
        }

        @Override
        public Camera.NearPlane getNearPlane() {
            return real.getNearPlane();
        }

        @Override
        public net.minecraft.world.level.material.FogType getFluidInCamera() {
            return real.getFluidInCamera();
        }

        @Override
        public net.minecraft.world.level.block.state.BlockState getBlockAtCamera() {
            return real.getBlockAtCamera();
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
            // Shift the camera the other way rather than the pose. Every beam renderer
            // anchors itself with (blockPos - camera.getPosition()), and Extra Lights'
            // raymarched shaft goes further: it feeds the shader world-space origins from
            // the block cell plus the camera position, and only uses the pose to place its
            // proxy box. Translating the pose moved that box off the volume it bounds; the
            // volume stayed on the grid, and the two drifted apart by up to a block before
            // snapping back at each cell boundary. Moving the camera shifts all of it as one.
            inner.render(bufferSource, poseStack, new ShiftedCamera(camera, delta), partialTick);
        }

        @Override
        public Vec3 getPos(float partialTick) {
            return inner.getPos(partialTick).add(delta);
        }
    }
}
