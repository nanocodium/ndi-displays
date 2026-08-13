package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nano.ndidisplays.block.MultiviewBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws the multiview control monitor: a flat 16:9 wall screen split into a 2x2 or
 * 3x3 grid of NDI cells. Each cell is a direct emissive video quad (a monitor, not a
 * simulated LED wall), separated by thin dark borders, with the source name captioned
 * under the cell.
 */
public class MultiviewRenderer implements BlockEntityRenderer<MultiviewBlockEntity> {

    private static final float THICKNESS = 0.10F;
    private static final float SURFACE_EPSILON = 0.004F;
    /** Bezel around the mosaic and border between cells, blocks. */
    private static final float BEZEL = 0.05F;
    private static final float BORDER = 0.03F;
    /** Caption band height inside the bottom of each cell, blocks. */
    private static final float CAPTION_H = 0.14F;

    @Override
    public void render(MultiviewBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }

        float w = be.getScreenWidth();
        float h = be.getScreenHeight();
        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        // Viewer-left to viewer-right for someone standing on the facing side.
        Vec3 uAxis = Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 center = new Vec3(0.5, 0.5, 0.5);

        Vec3 faceCenter = center.add(fwd.scale(THICKNESS * 0.5 + SURFACE_EPSILON));
        Vec3 backCenter = center.subtract(fwd.scale(THICKNESS * 0.5));
        // Top-left corner of the monitor face (viewer's perspective).
        Vec3 topLeft = faceCenter.subtract(uAxis.scale(w * 0.5)).add(up.scale(h * 0.5));

        Matrix4f mat = poseStack.last().pose();

        // --- Housing: dark front frame, back plate and edge band.
        VertexConsumer solid = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        Vec3[] frontCorners = {
                topLeft,
                topLeft.add(uAxis.scale(w)),
                topLeft.add(uAxis.scale(w)).subtract(up.scale(h)),
                topLeft.subtract(up.scale(h))
        };
        Vec3 backOffset = fwd.scale(-(THICKNESS + SURFACE_EPSILON));
        // Back plate.
        litQuad(solid, mat, packedLight, 0.10F,
                frontCorners[3].add(backOffset), frontCorners[2].add(backOffset),
                frontCorners[1].add(backOffset), frontCorners[0].add(backOffset));
        // Edge band.
        for (int i = 0; i < 4; i++) {
            Vec3 a = frontCorners[i];
            Vec3 b = frontCorners[(i + 1) % 4];
            litQuad(solid, mat, packedLight, 0.16F, a, b, b.add(backOffset), a.add(backOffset));
        }
        // Front face behind the cells (shows through bezel and borders).
        litQuad(solid, mat, packedLight, 0.05F,
                frontCorners[0], frontCorners[1], frontCorners[2], frontCorners[3]);

        // --- Cells.
        int n = be.gridSize();
        float innerW = w - 2 * BEZEL;
        float innerH = h - 2 * BEZEL;
        float cellW = (innerW - (n - 1) * BORDER) / n;
        float cellH = (innerH - (n - 1) * BORDER) / n;
        Vec3 cellEps = fwd.scale(SURFACE_EPSILON);
        float bright = be.getBrightness();

        Font font = Minecraft.getInstance().font;

        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                int cell = row * n + col;
                String source = be.getSource(cell);
                Vec3 cellTopLeft = topLeft
                        .add(uAxis.scale(BEZEL + col * (cellW + BORDER)))
                        .subtract(up.scale(BEZEL + row * (cellH + BORDER)))
                        .add(cellEps);

                renderCell(buffers, mat, cellTopLeft, uAxis, up, fwd, cellW, cellH, source, bright);
                renderCaption(poseStack, buffers, font, cellTopLeft, uAxis, up, fwd,
                        cellW, cellH, cell, source);
            }
        }
    }

    private static void renderCell(MultiBufferSource buffers, Matrix4f mat, Vec3 topLeft,
                                   Vec3 uAxis, Vec3 up, Vec3 normal, float cw, float ch,
                                   String source, float bright) {
        ResourceLocation tex = null;
        float shade = 1.0F;
        if (!source.isEmpty()) {
            NdiStream stream = NdiManager.acquire(source);
            if (stream != null) {
                stream.uploadIfNeeded();
                tex = stream.getTextureLocation();
            }
        }
        if (tex == null) {
            // No feed: near-black cell so the grid stays readable.
            tex = FallbackTextures.whiteLocation();
            shade = 0.03F;
        }
        float r = shade * bright;

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        emissiveVertex(vc, mat, topLeft, 0, 0, normal, r);
        emissiveVertex(vc, mat, topLeft.add(uAxis.scale(cw)), 1, 0, normal, r);
        emissiveVertex(vc, mat, topLeft.add(uAxis.scale(cw)).subtract(up.scale(ch)), 1, 1, normal, r);
        emissiveVertex(vc, mat, topLeft.subtract(up.scale(ch)), 0, 1, normal, r);
    }

    /** Small "n: source" label along the bottom edge of the cell. */
    private static void renderCaption(PoseStack poseStack, MultiBufferSource buffers, Font font,
                                      Vec3 cellTopLeft, Vec3 uAxis, Vec3 up, Vec3 fwd,
                                      float cw, float ch, int cell, String source) {
        String label = (cell + 1) + (source.isEmpty() ? "" : ": " + source);
        float scale = CAPTION_H * 0.8F / 9.0F;
        int textWidth = font.width(label);
        float maxWidth = (cw - 0.04F) / scale;
        if (textWidth > maxWidth) {
            label = font.plainSubstrByWidth(label, (int) maxWidth);
            textWidth = font.width(label);
        }

        Vec3 anchor = cellTopLeft
                .add(uAxis.scale(cw * 0.5))
                .subtract(up.scale(ch - CAPTION_H * 0.15F))
                .add(fwd.scale(0.002));

        poseStack.pushPose();
        poseStack.translate(anchor.x, anchor.y, anchor.z);
        float yaw = (float) Math.toDegrees(Math.atan2(fwd.x, fwd.z));
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
        poseStack.scale(scale, -scale, scale);
        font.drawInBatch(label, -textWidth / 2.0F, -9.0F, 0xFFE0E0E0, false,
                poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL,
                0x90000000, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private static void litQuad(VertexConsumer vc, Matrix4f mat, int light, float shade,
                                Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        litVertex(vc, mat, a, shade, light);
        litVertex(vc, mat, b, shade, light);
        litVertex(vc, mat, c, shade, light);
        litVertex(vc, mat, d, shade, light);
    }

    private static void litVertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float shade, int light) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(shade, shade, shade, 1.0F)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0, 1, 0)
                .endVertex();
    }

    private static void emissiveVertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float u, float v,
                                       Vec3 normal, float shade) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(shade, shade, shade, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(MultiviewBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
