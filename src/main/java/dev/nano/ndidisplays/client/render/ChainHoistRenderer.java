package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import javax.annotation.Nullable;

/**
 * Draws the load chain and the hook underneath a chain hoist.
 *
 * The chain is not made of blocks. Blocks would occupy the space the load has to travel
 * through, would need placing and breaking twenty times a second while the motor runs, and
 * would each need a block entity to know which hoist they belong to. Instead the motor
 * carries one number — metres of chain out — and this renderer draws that many links
 * below the block, continuing the load fall that is baked into the hoist model itself,
 * with the last link cut short so a 4.35 m chain looks like 4.35 m rather than snapping
 * to five.
 *
 * When the motor is idle, the chain also snaps to a solid sitting just under the hook.
 * That is the usual "I placed a block under it" case: the configured length is a little
 * short, the hook was reserving an empty metre, and the operator sees a gap. While the
 * load is flying the hook sits on the truss — including after it has tilted — so the
 * chain is a line from the motor to that point rather than a vertical drop the length
 * of a number.
 */
public class ChainHoistRenderer implements BlockEntityRenderer<ChainHoistBlockEntity> {

    /** One repeat of the link model, in blocks. */
    private static final float LINK_HEIGHT = 1.0F;

    /**
     * Visual height of {@code chain_hook.json}, from the load face (model y=0) up to the
     * swivel eye (model y=7.6). The chain run stops there so the last link meets the eye
     * instead of leaving a one-block hole above a tiny hook.
     */
    private static final float HOOK_HEIGHT = 7.6F / 16.0F;

    /**
     * How far past the configured length an idle chain will reach to meet a block. Wide
     * enough to close the old one-block hook reservation and a block placed just under
     * the hook; narrow enough that a 2 m chain over a superflat floor stays 2 m.
     */
    private static final float SNAP_SLACK = 2.0F;

    public static final ResourceLocation CHAIN_LINK_MODEL =
            new ResourceLocation(NdiDisplays.MODID, "block/chain_link");
    public static final ResourceLocation CHAIN_HOOK_MODEL =
            new ResourceLocation(NdiDisplays.MODID, "block/chain_hook");

    public ChainHoistRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(ChainHoistBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        float chain = visualChain(be, partialTick);
        if (chain <= 0.01F) {
            return;
        }

        BakedModel link = model(CHAIN_LINK_MODEL);
        BakedModel hook = model(CHAIN_HOOK_MODEL);
        if (link == null) {
            return;
        }

        BlockState state = be.getBlockState();
        VertexConsumer cutout = buffers.getBuffer(RenderType.cutout());

        poseStack.pushPose();
        // Default: straight down from the housing, which is what a level hang looks like.
        // When the load is raked the hook is no longer under the motor, so the whole
        // run is rotated to meet it.
        Vec3 hookWorld = be.flyingHookWorld(partialTick);
        if (hookWorld != null) {
            BlockPos motor = be.getBlockPos();
            Vec3 start = new Vec3(motor.getX() + 0.5, motor.getY(), motor.getZ() + 0.5);
            Vec3 delta = hookWorld.subtract(start);
            float length = (float) delta.length();
            if (length > 0.01F) {
                chain = length;
                poseStack.translate(0.5, 0, 0.5);
                Vec3 dir = delta.scale(1.0 / length);
                poseStack.mulPose(new Quaternionf().rotationTo(
                        0.0F, -1.0F, 0.0F,
                        (float) dir.x, (float) dir.y, (float) dir.z));
                poseStack.translate(-0.5, 0, -0.5);
            }
        }

        float run = Math.max(0.0F, chain - HOOK_HEIGHT);
        float y = 0;
        while (run > 0.01F) {
            float segment = Math.min(LINK_HEIGHT, run);
            poseStack.pushPose();
            poseStack.translate(0, y - segment, 0);
            if (segment < LINK_HEIGHT) {
                // Short last link: squash the model rather than letting the chain
                // overshoot the hook by up to a metre.
                poseStack.scale(1.0F, segment / LINK_HEIGHT, 1.0F);
            }
            // Light the chain where it actually hangs; a long drop into a dark stage
            // should not stay lit by the roof it is bolted to.
            int light = lightAt(level, be.getBlockPos(), y - segment);
            renderModel(poseStack, cutout, state, link, light);
            poseStack.popPose();
            y -= segment;
            run -= segment;
        }

        if (hook != null) {
            poseStack.pushPose();
            poseStack.translate(0, -chain, 0);
            renderModel(poseStack, cutout, state, hook, lightAt(level, be.getBlockPos(), -chain));
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /**
     * Metres of chain to draw this frame.
     *
     * Flying, the motor already knows: its length is the distance to the load, which is
     * not in the world. Idle, a solid sitting just under the hook wins — that is how a
     * block placed under a slightly-too-short chain gets a hook sitting on it instead of
     * a gap.
     */
    private static float visualChain(ChainHoistBlockEntity be, float partialTick) {
        float chain = be.renderChain(partialTick);
        if (be.isAttached() || be.getLevel() == null) {
            return chain;
        }
        Float toSolid = solidDistance(be.getLevel(), be.getBlockPos(), chain + SNAP_SLACK);
        if (toSolid != null && toSolid >= 0.0F && toSolid <= chain + SNAP_SLACK) {
            return toSolid;
        }
        return chain;
    }

    @Nullable
    private static Float solidDistance(Level level, BlockPos motor, float limit) {
        int max = Math.max(1, (int) Math.ceil(limit));
        BlockPos.MutableBlockPos cursor = motor.mutable();
        for (int i = 1; i <= max; i++) {
            cursor.move(Direction.DOWN);
            if (!level.isLoaded(cursor)) {
                return null;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            return (float) (motor.getY() - (cursor.getY() + 1));
        }
        return null;
    }

    private static int lightAt(Level level, BlockPos motor, float localY) {
        return LevelRenderer.getLightColor(level, motor.offset(0, (int) Math.floor(localY), 0));
    }

    private static BakedModel model(ResourceLocation location) {
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(location);
        return model == Minecraft.getInstance().getModelManager().getMissingModel() ? null : model;
    }

    private static void renderModel(PoseStack poseStack, VertexConsumer vc, BlockState state,
                                    BakedModel model, int light) {
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                poseStack.last(), vc, state, model, 1.0F, 1.0F, 1.0F,
                light, OverlayTexture.NO_OVERLAY);
    }

    @Override
    public boolean shouldRenderOffScreen(ChainHoistBlockEntity be) {
        // The hook can hang far below the motor's own chunk section.
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
