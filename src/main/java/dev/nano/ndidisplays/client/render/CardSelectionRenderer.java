package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.item.NdiConfigCardItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the NDI card's WorldEdit-style selection while the card is held: a cyan
 * wireframe over corner 1, and the full selection box once corner 2 is set — so an
 * operator patching a 250-screen array can see exactly which motors the apply will hit.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CardSelectionRenderer {

    private CardSelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        ItemStack card = heldCard(player);
        if (card == null) {
            return;
        }
        // Selections from another dimension are real but invisible here; don't draw them.
        if (!NdiConfigCardItem.selectionDimension(card).isEmpty()
                && !NdiConfigCardItem.selectionDimension(card)
                        .equals(mc.level.dimension().location().toString())) {
            return;
        }
        BlockPos pos1 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS1);
        BlockPos pos2 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS2);
        if (pos1 == null && pos2 == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        // Each set corner gets its own bright marker, and the spanned box is drawn once
        // both exist — mirroring how WorldEdit's CUI presents a cuboid selection.
        if (pos1 != null) {
            LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()),
                    new AABB(pos1), 0.2F, 1.0F, 1.0F, 1.0F);
        }
        if (pos2 != null) {
            LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()),
                    new AABB(pos2), 1.0F, 0.5F, 0.1F, 1.0F);
        }
        if (pos1 != null && pos2 != null) {
            AABB box = new AABB(
                    Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ()),
                    Math.max(pos1.getX(), pos2.getX()) + 1.0, Math.max(pos1.getY(), pos2.getY()) + 1.0,
                    Math.max(pos1.getZ(), pos2.getZ()) + 1.0)
                    .inflate(0.01);
            LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()),
                    box, 0.2F, 0.9F, 1.0F, 0.8F);
        }

        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static ItemStack heldCard(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof NdiConfigCardItem) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().getItem() instanceof NdiConfigCardItem) {
            return player.getOffhandItem();
        }
        return null;
    }
}
