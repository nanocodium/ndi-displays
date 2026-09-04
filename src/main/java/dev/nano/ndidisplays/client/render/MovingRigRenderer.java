package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nano.ndidisplays.compat.theatrical.HoistFixtureCompat;
import dev.nano.ndidisplays.entity.MovingRigEntity;
import dev.nano.ndidisplays.hoist.RigStructure;
import dev.nano.ndidisplays.hoist.RigTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Draws a structure while it is in the air.
 *
 * The load's blocks are not in the world during a move, so nothing else would draw them.
 * Each captured state is rendered from its baked model at the entity's interpolated
 * position, which is what makes a truss glide instead of stepping a block at a time. When
 * the motors are at different heights the whole load is turned about the plane of its
 * hooks, so a raked truss looks raked rather than staying stubbornly square.
 *
 * Some blocks hide that baked model on purpose — a speaker whose wrench pose is stored on
 * the block entity, a TESR cabinet, a lighting fixture with a moving head. Those would
 * vanish for the whole flight and pop back on landing. For those, this renderer keeps a
 * short-lived client copy of the block entity and asks its own renderer to draw it, so the
 * pose survives the trip. Lighting fixtures go one step further: their live DMX state
 * arrives on the entity every tick, so a flown moving head goes on running its cue, beam
 * and all, instead of freezing at the look it took off with.
 */
public class MovingRigRenderer extends EntityRenderer<MovingRigEntity> {

    /**
     * Ghost block entities, keyed by the flying rig. Rebuilt when the snapshot or the
     * load's attitude changes, not every frame — constructing a speaker's block entity is
     * cheap, doing it two hundred times a second for a line array is not.
     */
    private final WeakHashMap<MovingRigEntity, Ghosts> ghosts = new WeakHashMap<>();

    public MovingRigRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(MovingRigEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        RigStructure structure = entity.structure();
        Level level = entity.level();
        if (structure == null || level == null || structure.size() == 0) {
            return;
        }

        // The entity's origin is the rig origin, and the render pose is already there, so
        // the transform used here carries the slope only — adding its vertical travel
        // again would count the move twice.
        RigTransform tilt = entity.renderTransform();
        boolean sloped = !tilt.flat();
        Quaternionf rotation = sloped ? tilt.rotation() : null;

        BlockPos originBlock = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
        Ghosts cache = ghosts(entity, structure, level, originBlock, tilt);
        cache.pushLiveFixtures(entity, structure);

        for (int i = 0; i < structure.entries().size(); i++) {
            RigStructure.Entry entry = structure.entries().get(i);
            BlockPos offset = entry.offset();
            BlockState state = entry.state();
            int light = LevelRenderer.getLightColor(level, tilt.cellOf(originBlock, offset));

            poseStack.pushPose();
            if (sloped) {
                // Turn each block about its own centre, then step back to the corner the
                // block renderer expects to start from.
                Vec3 centre = tilt.apply(offset.getX() + 0.5, offset.getY() + 0.5,
                        offset.getZ() + 0.5);
                poseStack.translate(centre.x, centre.y, centre.z);
                poseStack.mulPose(rotation);
                poseStack.translate(-0.5, -0.5, -0.5);
            } else {
                poseStack.translate(offset.getX(), offset.getY(), offset.getZ());
            }

            boolean drew = false;
            if (RigStructure.clientNeedsBlockEntity(state)) {
                drew = renderGhost(cache.entities().get(i), partialTick, poseStack, buffers,
                        light);
                if (!drew) {
                    BlockState visible = visibleFallback(state);
                    if (visible.getRenderShape() != RenderShape.INVISIBLE) {
                        renderBlock(visible, poseStack, buffers, light);
                        drew = true;
                    }
                }
            }
            if (!drew && state.getRenderShape() != RenderShape.INVISIBLE) {
                renderBlock(state, poseStack, buffers, light);
            }
            poseStack.popPose();
        }
    }

    /**
     * Asks the block's own renderer to draw a ghost. The dispatcher is not used: it
     * would translate from the ghost's world position a second time, and this pose is
     * already sitting on the flying block.
     */
    private static boolean renderGhost(@Nullable BlockEntity ghost, float partialTick,
                                       PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (ghost == null) {
            return false;
        }
        BlockEntityRenderDispatcher dispatcher =
                Minecraft.getInstance().getBlockEntityRenderDispatcher();
        BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(ghost);
        if (renderer == null) {
            return false;
        }
        try {
            renderer.render(ghost, partialTick, poseStack, buffers, light,
                    OverlayTexture.NO_OVERLAY);
            return true;
        } catch (RuntimeException ignored) {
            // A foreign renderer that assumes it is in the world (neighbours, a mixer
            // down the hall) must not take the rest of the rig with it.
            return false;
        }
    }

    private static void renderBlock(BlockState state, PoseStack poseStack,
                                    MultiBufferSource buffers, int light) {
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                state, poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
    }

    /**
     * The baked model a wrench-hidden block would show if it were not being posed.
     *
     * SEF (and a few other furniture mods) flip an integer {@code state} or {@code angle}
     * to hide the model and draw it from the block entity instead. Putting that property
     * back to zero recovers the cabinet, without the wrench pose, when the ghost renderer
     * is missing or throws.
     */
    private static BlockState visibleFallback(BlockState state) {
        BlockState out = state;
        for (Property<?> property : state.getProperties()) {
            if (!(property instanceof IntegerProperty ints)) {
                continue;
            }
            String name = ints.getName();
            int value = state.getValue(ints);
            if ("state".equals(name) && value != 0 && ints.getPossibleValues().contains(0)) {
                out = out.setValue(ints, 0);
            } else if ("angle".equals(name) && value == 3 && ints.getPossibleValues().contains(0)) {
                out = out.setValue(ints, 0);
            }
        }
        return out;
    }

    private Ghosts ghosts(MovingRigEntity entity, RigStructure structure, Level level,
                          BlockPos origin, RigTransform tilt) {
        // A block entity is addressed by a position it cannot be told to change, so the
        // ghosts have to be rebuilt whenever the load's cells move. That is every block of
        // travel, and every couple of degrees of slope.
        long attitude = (Math.round(tilt.gradX() * 32) << 20) ^ Math.round(tilt.gradZ() * 32);
        Ghosts cache = ghosts.get(entity);
        if (cache != null && cache.source == structure && origin.equals(cache.origin)
                && attitude == cache.attitude) {
            return cache;
        }
        cache = new Ghosts(structure, origin, attitude);
        for (RigStructure.Entry entry : structure.entries()) {
            cache.entities().add(
                    createGhost(level, tilt.cellOf(origin, entry.offset()), entry));
        }
        ghosts.put(entity, cache);
        return cache;
    }

    @Nullable
    private static BlockEntity createGhost(Level level, BlockPos worldPos,
                                           RigStructure.Entry entry) {
        if (!RigStructure.clientNeedsBlockEntity(entry.state())) {
            return null;
        }
        if (!(entry.state().getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity ghost = entityBlock.newBlockEntity(worldPos, entry.state());
        if (ghost == null) {
            return null;
        }
        ghost.setLevel(level);
        CompoundTag tag = entry.blockEntity();
        if (tag != null) {
            CompoundTag placed = tag.copy();
            placed.putInt("x", worldPos.getX());
            placed.putInt("y", worldPos.getY());
            placed.putInt("z", worldPos.getZ());
            try {
                ghost.load(placed);
            } catch (RuntimeException ignored) {
                // The ghost is only for drawing. A tag this renderer does not understand
                // still leaves a block entity the foreign renderer can refuse, and the
                // visible fallback then takes over.
            }
        }
        return ghost;
    }

    @Override
    public ResourceLocation getTextureLocation(MovingRigEntity entity) {
        // Every block brings its own texture; the entity itself has none.
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }

    /** One rig's ghost block entities, plus the last lighting state pushed onto them. */
    private static final class Ghosts {
        private final RigStructure source;
        private final BlockPos origin;
        private final long attitude;
        private final List<BlockEntity> entities = new ArrayList<>();
        private CompoundTag appliedLive = new CompoundTag();

        Ghosts(RigStructure source, BlockPos origin, long attitude) {
            this.source = source;
            this.origin = origin;
            this.attitude = attitude;
        }

        List<BlockEntity> entities() {
            return entities;
        }

        /**
         * Copies the rig's live lighting state onto the ghosts, when it has changed.
         *
         * Once per change rather than once per frame: a fixture reloads itself from NBT to
         * take the values, and re-running that at the frame rate for a truss of moving
         * heads would be measurable for no benefit — nothing looks at it in between.
         */
        void pushLiveFixtures(MovingRigEntity entity, RigStructure structure) {
            CompoundTag live = entity.fixtureState();
            if (live.isEmpty() || live.equals(appliedLive)) {
                return;
            }
            HoistFixtureCompat.applyLive(live, entities, structure);
            appliedLive = live.copy();
        }
    }
}
