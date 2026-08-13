package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.compat.theatrical.FixtureModelData;
import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A Theatrical / Extra Lights fixture flown from a kinetic winch. Theatrical's own
 * renderers are anchored on the placed block position, so the winch draws the
 * fixture itself: the fixture's baked static/pan/tilt models hanging head-down at
 * the hook, pan/tilt swept by the winch's 10-channel DMX head state, plus a simple
 * translucent beam cone in the head's colour.
 */
final class FlownFixtureRenderer {

    /** Beam length drawn, blocks (visual only, no raymarch like Theatrical's). */
    private static final float BEAM_LENGTH = 12.0F;

    private FlownFixtureRenderer() {
    }

    static void render(KineticWinchBlockEntity be, float partialTick, double hookY,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        FixtureModelData data = TheatricalCompat.fixtureModelData(be.getFixtureBlockId());
        Matrix4f mat = poseStack.last().pose();
        if (data == null) {
            // Theatrical absent or no fixture loaded yet: a small dark head placeholder.
            VertexConsumer solid = buffers.getBuffer(
                    RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
            KineticPanelRenderer.placeholderBox(solid, mat,
                    new Vec3(0.5 - 0.16, hookY - 0.45, 0.5 - 0.16),
                    new Vec3(0.5 + 0.16, hookY - 0.08, 0.5 + 0.16), packedLight);
            return;
        }

        float pan = be.getRenderPan(partialTick);
        float tilt = be.getRenderTilt(partialTick);
        Direction facing = be.getFacing();

        poseStack.pushPose();
        // The fixture model occupies its own block space; hang it head-down directly
        // under the hook, yawed to the winch's facing — the same flip Theatrical
        // applies to a fixture bolted under a truss (isFlipped path).
        poseStack.translate(0, hookY - 1.0, 0);
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(facing.toYRot()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180));
        poseStack.translate(-0.5, -0.5, -0.5);

        VertexConsumer cutout = buffers.getBuffer(RenderType.cutout());
        BakedModel staticModel = model(data.staticModel());
        if (staticModel != null) {
            renderModel(poseStack, cutout, data, staticModel, packedLight);
        }

        // Pan stage.
        float[] pans = data.panPivot();
        poseStack.translate(pans[0], pans[1], pans[2]);
        poseStack.mulPose(Axis.YP.rotationDegrees(pan));
        poseStack.translate(-pans[0], -pans[1], -pans[2]);
        BakedModel panModel = data.panModel() != null ? model(data.panModel()) : null;
        if (panModel != null) {
            renderModel(poseStack, cutout, data, panModel, packedLight);
        }

        // Tilt stage (the head). Matches Theatrical's flipped fixture path: a 180°
        // base rotation on X, then the swept tilt.
        float[] tilts = data.tiltPivot();
        poseStack.translate(tilts[0], tilts[1], tilts[2]);
        poseStack.mulPose(Axis.XP.rotationDegrees(-180));
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        poseStack.translate(-tilts[0], -tilts[1], -tilts[2]);
        BakedModel tiltModel = data.tiltModel() != null ? model(data.tiltModel()) : null;
        if (tiltModel != null) {
            renderModel(poseStack, cutout, data, tiltModel, packedLight);
        }

        // Beam: a translucent cone out of the lens along the head's axis, coloured by
        // the DMX head state and scaled by intensity. Drawn in the tilt space so it
        // follows every move.
        float intensity = be.getFixtureIntensity();
        if (intensity > 0.01F) {
            float[] rgb = be.getFixtureColor();
            float[] beamStart = data.beamStart();
            poseStack.pushPose();
            poseStack.translate(beamStart[0], beamStart[1], beamStart[2]);
            drawBeam(poseStack, buffers, data.beamWidth(), be.getFixtureFocus(),
                    rgb[0], rgb[1], rgb[2], intensity * 0.35F);
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private static BakedModel model(net.minecraft.resources.ResourceLocation location) {
        return Minecraft.getInstance().getModelManager().getModel(location);
    }

    private static void renderModel(PoseStack poseStack, VertexConsumer vc,
                                    FixtureModelData data, BakedModel model, int packedLight) {
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                poseStack.last(), vc, data.state(), model, 1.0F, 1.0F, 1.0F,
                packedLight, OverlayTexture.NO_OVERLAY);
    }

    /**
     * Theatrical-style beam cross: four fading quads flaring with focus, drawn along
     * -Z of the current pose (the head's optical axis after the tilt transforms).
     */
    private static void drawBeam(PoseStack poseStack, MultiBufferSource buffers,
                                 float beamSize, float focus, float r, float g, float b,
                                 float alpha) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = poseStack.last().pose();
        float len = BEAM_LENGTH;
        float endMul = 1.0F + focus * len * 0.06F;
        int a = (int) (alpha * 255);
        int cr = (int) (r * 255);
        int cg = (int) (g * 255);
        int cb = (int) (b * 255);

        beamQuad(vc, m, cr, cg, cb, a, beamSize, endMul, len, true, false);
        beamQuad(vc, m, cr, cg, cb, a, beamSize, endMul, len, true, true);
        beamQuad(vc, m, cr, cg, cb, a, beamSize, endMul, len, false, false);
        beamQuad(vc, m, cr, cg, cb, a, beamSize, endMul, len, false, true);
    }

    private static void beamQuad(VertexConsumer vc, Matrix4f m, int r, int g, int b, int a,
                                 float size, float endMul, float len, boolean xPlane, boolean flip) {
        float s = flip ? -size : size;
        float e = s * endMul;
        if (xPlane) {
            beamVertex(vc, m, r, g, b, 0, e, size * endMul, -len);
            beamVertex(vc, m, r, g, b, a, s, size, 0);
            beamVertex(vc, m, r, g, b, a, s, -size, 0);
            beamVertex(vc, m, r, g, b, 0, e, -size * endMul, -len);
        } else {
            beamVertex(vc, m, r, g, b, 0, size * endMul, e, -len);
            beamVertex(vc, m, r, g, b, a, size, s, 0);
            beamVertex(vc, m, r, g, b, a, -size, s, 0);
            beamVertex(vc, m, r, g, b, 0, -size * endMul, e, -len);
        }
    }

    private static void beamVertex(VertexConsumer vc, Matrix4f m, int r, int g, int b, int a,
                                   float x, float y, float z) {
        vc.vertex(m, x, y, z).color(r, g, b, a).endVertex();
    }
}
