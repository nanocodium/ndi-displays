package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.block.WinchParkMonitorBlockEntity;
import dev.nano.ndidisplays.winch.WinchParkLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Control-room park plot: a 16:9 monitor with a dark bezel, header strip, and a
 * live Xine-style grid of motor wells whose fill is the current drop.
 */
public class WinchParkMonitorRenderer implements BlockEntityRenderer<WinchParkMonitorBlockEntity> {

    private static final float THICKNESS = 0.12F;
    private static final float SURFACE_EPSILON = 0.004F;
    private static final float BEZEL = 0.07F;
    private static final float HEADER = 0.20F;
    private static final float HAIRLINE = 0.012F;

    @Override
    public void render(WinchParkMonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }

        float w = be.getScreenWidth();
        float h = be.getScreenHeight();
        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 uAxis = Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 center = new Vec3(0.5, 0.5, 0.5);
        Vec3 faceCenter = center.add(fwd.scale(THICKNESS * 0.5 + SURFACE_EPSILON));
        Vec3 topLeft = faceCenter.subtract(uAxis.scale(w * 0.5)).add(up.scale(h * 0.5));
        Matrix4f mat = poseStack.last().pose();

        VertexConsumer solid = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        Vec3[] front = {
                topLeft,
                topLeft.add(uAxis.scale(w)),
                topLeft.add(uAxis.scale(w)).subtract(up.scale(h)),
                topLeft.subtract(up.scale(h))
        };
        Vec3 backOff = fwd.scale(-(THICKNESS + SURFACE_EPSILON));
        litRgb(solid, mat, packedLight, 0.07F, 0.07F, 0.08F,
                front[3].add(backOff), front[2].add(backOff), front[1].add(backOff), front[0].add(backOff));
        for (int i = 0; i < 4; i++) {
            Vec3 a = front[i];
            Vec3 b = front[(i + 1) % 4];
            litRgb(solid, mat, packedLight, 0.18F, 0.19F, 0.21F, a, b, b.add(backOff), a.add(backOff));
        }
        // Front bezel / glass backing.
        litRgb(solid, mat, packedLight, 0.035F, 0.038F, 0.045F, front[0], front[1], front[2], front[3]);

        VertexConsumer glow = buffers.getBuffer(
                RenderType.entityTranslucentEmissive(FallbackTextures.whiteLocation()));
        Vec3 bump = fwd.scale(SURFACE_EPSILON);
        Vec3 innerTL = topLeft.add(uAxis.scale(BEZEL)).subtract(up.scale(BEZEL)).add(bump);
        float innerW = w - 2 * BEZEL;
        float innerH = h - 2 * BEZEL;

        // Hairline around the glass.
        strokeRect(glow, mat, innerTL, uAxis, up, fwd, innerW, innerH, HAIRLINE,
                0.18F, 0.55F, 0.62F, 0.55F);

        boolean bound = be.isBoundIn(be.getLevel());
        List<WinchParkLayout.Motor> motors = bound
                ? WinchParkLayout.layout(WinchParkLayout.collect(
                        be.getLevel(), be.getParkPos1(), be.getParkPos2()))
                : List.of();

        // Header strip.
        emissiveQuad(glow, mat, innerTL, uAxis, up, fwd, innerW, HEADER,
                0.04F, 0.07F, 0.09F, 0.95F);
        float led = 0.055F;
        float[] status = !bound
                ? new float[]{0.75F, 0.18F, 0.18F}
                : motors.isEmpty()
                ? new float[]{0.85F, 0.55F, 0.12F}
                : new float[]{0.20F, 0.90F, 0.72F};
        emissiveQuad(glow, mat,
                innerTL.add(uAxis.scale(0.05F)).subtract(up.scale((HEADER - led) * 0.5F)),
                uAxis, up, fwd, led, led, status[0], status[1], status[2], 1.0F);

        Font font = Minecraft.getInstance().font;
        String title = bound
                ? (motors.isEmpty() ? "PARK  —" : String.format("PARK  %d  ·  %d\u00D7%d",
                motors.size(), motors.get(0).cols(), motors.get(0).rows()))
                : "PARK  NO SIGNAL";
        drawLabel(poseStack, buffers, font,
                innerTL.add(uAxis.scale(0.14F)).subtract(up.scale(HEADER * 0.28F)).add(fwd.scale(0.002)),
                fwd, HEADER * 0.42F, title, 0xFFB8E8E0, false);

        if (!bound || motors.isEmpty()) {
            String idle = bound ? "NO MOTORS IN RANGE" : "BIND WITH NDI CARD";
            drawLabel(poseStack, buffers, font,
                    innerTL.add(uAxis.scale(innerW * 0.5F)).subtract(up.scale(innerH * 0.55F)).add(fwd.scale(0.002)),
                    fwd, 0.14F, idle, 0xFF5A6A70, true);
            return;
        }

        float plotTop = HEADER + 0.04F;
        float plotH = innerH - plotTop - 0.03F;
        float plotW = innerW - 0.04F;
        Vec3 plotTL = innerTL.add(uAxis.scale(0.02F)).subtract(up.scale(plotTop)).add(fwd.scale(SURFACE_EPSILON));
        int cols = motors.get(0).cols();
        int rows = motors.get(0).rows();
        float gap = Math.min(0.035F, Math.min(plotW / cols, plotH / rows) * 0.14F);
        float cellW = (plotW - (cols - 1) * gap) / cols;
        float cellH = (plotH - (rows - 1) * gap) / rows;
        boolean labelCells = motors.size() <= 64 && cellW > 0.18F && cellH > 0.18F;

        for (WinchParkLayout.Motor motor : motors) {
            Vec3 cellTL = plotTL
                    .add(uAxis.scale(motor.gridX() * (cellW + gap)))
                    .subtract(up.scale(motor.gridZ() * (cellH + gap)));
            drawMotorCell(glow, mat, poseStack, buffers, font, cellTL, uAxis, up, fwd,
                    cellW, cellH, motor.be(), partialTick, labelCells);
        }
    }

    private static void drawMotorCell(VertexConsumer glow, Matrix4f mat, PoseStack poseStack,
                                      MultiBufferSource buffers, Font font,
                                      Vec3 cellTL, Vec3 uAxis, Vec3 up, Vec3 fwd,
                                      float cellW, float cellH, KineticWinchBlockEntity winch,
                                      float partialTick, boolean label) {
        emissiveQuad(glow, mat, cellTL, uAxis, up, fwd, cellW, cellH,
                0.05F, 0.055F, 0.065F, 0.92F);
        strokeRect(glow, mat, cellTL, uAxis, up, fwd, cellW, cellH, HAIRLINE * 0.7F,
                0.12F, 0.16F, 0.18F, 0.7F);

        float inset = Math.min(cellW, cellH) * 0.16F;
        float wellW = cellW - 2 * inset;
        float wellH = cellH - 2 * inset - (label ? cellH * 0.18F : 0);
        Vec3 wellTL = cellTL.add(uAxis.scale(inset)).subtract(up.scale(inset));
        emissiveQuad(glow, mat, wellTL, uAxis, up, fwd, wellW, wellH,
                0.015F, 0.018F, 0.022F, 1.0F);

        float span = Math.max(0.01F, winch.getMaxDrop() - winch.getMinDrop());
        float nA = clamp01((winch.getRenderDrop(partialTick) - winch.getMinDrop()) / span);
        float[] rgb = payloadRgb(winch.getPayload());
        boolean twin = winch.isTwinMode() && winch.getPayload() == KineticWinchBlockEntity.PAYLOAD_LED_TILE;
        if (twin) {
            float nB = clamp01((winch.getRenderDropB(partialTick) - winch.getMinDrop()) / span);
            float split = wellW * 0.08F;
            float barW = (wellW - split) * 0.5F;
            drawDropBar(glow, mat, wellTL, uAxis, up, fwd, barW, wellH, nA, rgb);
            drawDropBar(glow, mat, wellTL.add(uAxis.scale(barW + split)), uAxis, up, fwd, barW, wellH, nB, rgb);
        } else {
            drawDropBar(glow, mat, wellTL, uAxis, up, fwd, wellW, wellH, nA, rgb);
        }

        if (label) {
            String addr = String.valueOf(winch.getDmxAddress());
            drawLabel(poseStack, buffers, font,
                    cellTL.add(uAxis.scale(cellW * 0.5F)).subtract(up.scale(cellH - inset * 0.7F))
                            .add(fwd.scale(0.002)),
                    fwd, cellH * 0.16F, addr, 0xFF8AA0A8, true);
        }
    }

    private static void drawDropBar(VertexConsumer glow, Matrix4f mat, Vec3 wellTL,
                                    Vec3 uAxis, Vec3 up, Vec3 fwd,
                                    float barW, float wellH, float n, float[] rgb) {
        float fill = Math.max(0.04F, n) * wellH;
        Vec3 barTL = wellTL.subtract(up.scale(wellH - fill));
        emissiveQuad(glow, mat, barTL, uAxis, up, fwd, barW, fill,
                rgb[0] * 0.55F, rgb[1] * 0.55F, rgb[2] * 0.55F, 1.0F);
        float cap = Math.min(0.03F, fill * 0.22F);
        emissiveQuad(glow, mat, barTL, uAxis, up, fwd, barW, cap,
                rgb[0], rgb[1], rgb[2], 1.0F);
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static float[] payloadRgb(int payload) {
        return switch (payload) {
            case KineticWinchBlockEntity.PAYLOAD_SLAT -> new float[]{0.28F, 0.92F, 0.58F};
            case KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE -> new float[]{0.95F, 0.34F, 0.72F};
            case KineticWinchBlockEntity.PAYLOAD_MIRROR_BALL -> new float[]{0.82F, 0.86F, 0.90F};
            case KineticWinchBlockEntity.PAYLOAD_FIXTURE -> new float[]{0.98F, 0.64F, 0.22F};
            default -> new float[]{0.22F, 0.78F, 0.92F};
        };
    }

    private static void strokeRect(VertexConsumer vc, Matrix4f mat, Vec3 tl,
                                   Vec3 uAxis, Vec3 up, Vec3 normal,
                                   float w, float h, float t,
                                   float r, float g, float b, float a) {
        emissiveQuad(vc, mat, tl, uAxis, up, normal, w, t, r, g, b, a);
        emissiveQuad(vc, mat, tl.subtract(up.scale(h - t)), uAxis, up, normal, w, t, r, g, b, a);
        emissiveQuad(vc, mat, tl, uAxis, up, normal, t, h, r, g, b, a);
        emissiveQuad(vc, mat, tl.add(uAxis.scale(w - t)), uAxis, up, normal, t, h, r, g, b, a);
    }

    private static void drawLabel(PoseStack poseStack, MultiBufferSource buffers, Font font,
                                  Vec3 anchor, Vec3 fwd, float height, String text, int color,
                                  boolean center) {
        float scale = height / 9.0F;
        int textWidth = font.width(text);
        poseStack.pushPose();
        poseStack.translate(anchor.x, anchor.y, anchor.z);
        float yaw = (float) Math.toDegrees(Math.atan2(fwd.x, fwd.z));
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
        poseStack.scale(scale, -scale, scale);
        float x = center ? -textWidth / 2.0F : 0.0F;
        font.drawInBatch(text, x, 0.0F, color, false,
                poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private static void emissiveQuad(VertexConsumer vc, Matrix4f mat, Vec3 topLeft,
                                     Vec3 uAxis, Vec3 up, Vec3 normal,
                                     float cw, float ch, float r, float g, float b, float a) {
        emissive(vc, mat, topLeft, normal, r, g, b, a);
        emissive(vc, mat, topLeft.add(uAxis.scale(cw)), normal, r, g, b, a);
        emissive(vc, mat, topLeft.add(uAxis.scale(cw)).subtract(up.scale(ch)), normal, r, g, b, a);
        emissive(vc, mat, topLeft.subtract(up.scale(ch)), normal, r, g, b, a);
    }

    private static void emissive(VertexConsumer vc, Matrix4f mat, Vec3 pos, Vec3 normal,
                                 float r, float g, float b, float a) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, a)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }

    private static void litRgb(VertexConsumer vc, Matrix4f mat, int light,
                               float r, float g, float b, Vec3 a, Vec3 c2, Vec3 c3, Vec3 d) {
        lit(vc, mat, a, r, g, b, light);
        lit(vc, mat, c2, r, g, b, light);
        lit(vc, mat, c3, r, g, b, light);
        lit(vc, mat, d, r, g, b, light);
    }

    private static void lit(VertexConsumer vc, Matrix4f mat, Vec3 pos,
                            float r, float g, float b, int light) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, 1.0F)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0, 1, 0)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(WinchParkMonitorBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
