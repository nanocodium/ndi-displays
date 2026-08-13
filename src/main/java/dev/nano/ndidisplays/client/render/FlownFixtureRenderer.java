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

    /** Fallback beam length when the surface raycast can't run, blocks. */
    private static final float BEAM_LENGTH = 12.0F;
    /** How far the beam raycast looks for a surface, blocks. */
    private static final float MAX_BEAM_LENGTH = 40.0F;

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

        // Beam. Preferred path: Theatrical's own volumetric raymarch pipeline — the
        // exact light a truss-mounted fixture emits. It wants the head transform in
        // block-local space (it adds the BlockPos itself), so the transform chain is
        // rebuilt on an identity stack. Fallback: classic translucent cone quads.
        float intensity = be.getFixtureIntensity();
        if (intensity > 0.01F) {
            float[] rgb = be.getFixtureColor();
            if (rgb[0] + rgb[1] + rgb[2] < 0.01F) {
                // Dimmer up but no colour patched yet: open white, like a real lamp.
                rgb = new float[]{1.0F, 1.0F, 1.0F};
            }
            float[] beamStart = data.beamStart();
            Matrix4f head = localHeadMatrix(be, hookY, facing, pan, tilt, data, beamStart);

            Vec3 origin = Vec3.atLowerCornerOf(be.getBlockPos())
                    .add(head.m30(), head.m31(), head.m32());
            Vec3 dir = new Vec3(-head.m20(), -head.m21(), -head.m22()).normalize();
            float length = beamLength(be, origin, dir);

            boolean raymarched = TheatricalCompat.submitFixtureBeam(be.getBlockPos(), head,
                    data.beamWidth(), be.getFixtureFocus(),
                    rgb[0], rgb[1], rgb[2], intensity, length);
            if (!raymarched) {
                poseStack.pushPose();
                poseStack.translate(beamStart[0], beamStart[1], beamStart[2]);
                drawBeam(poseStack, buffers, data.beamWidth(), be.getFixtureFocus(), length,
                        rgb[0], rgb[1], rgb[2], intensity * 0.35F);
                poseStack.popPose();
            }
        }

        poseStack.popPose();
    }

    /**
     * The head transform in block-local space: the same chain as the render pass
     * (hang under the hook, yaw to facing, flip, pan stage, tilt stage, beam start),
     * but on an identity stack instead of the camera-relative BER stack.
     */
    private static Matrix4f localHeadMatrix(KineticWinchBlockEntity be, double hookY,
                                            Direction facing, float pan, float tilt,
                                            FixtureModelData data, float[] beamStart) {
        PoseStack local = new PoseStack();
        local.translate(0, hookY - 1.0, 0);
        local.translate(0.5, 0.5, 0.5);
        local.mulPose(Axis.YP.rotationDegrees(facing.toYRot()));
        local.mulPose(Axis.ZP.rotationDegrees(180));
        local.translate(-0.5, -0.5, -0.5);
        float[] pans = data.panPivot();
        local.translate(pans[0], pans[1], pans[2]);
        local.mulPose(Axis.YP.rotationDegrees(pan));
        local.translate(-pans[0], -pans[1], -pans[2]);
        float[] tilts = data.tiltPivot();
        local.translate(tilts[0], tilts[1], tilts[2]);
        local.mulPose(Axis.XP.rotationDegrees(-180));
        local.mulPose(Axis.XP.rotationDegrees(tilt));
        local.translate(-tilts[0], -tilts[1], -tilts[2]);
        local.translate(beamStart[0], beamStart[1], beamStart[2]);
        return new Matrix4f(local.last().pose());
    }

    /** Beam length to the first surface the light hits, like Theatrical's fixtures. */
    private static float beamLength(KineticWinchBlockEntity be, Vec3 origin, Vec3 dir) {
        var level = be.getLevel();
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) {
            return BEAM_LENGTH;
        }
        var hit = level.clip(new net.minecraft.world.level.ClipContext(
                origin, origin.add(dir.scale(MAX_BEAM_LENGTH)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return MAX_BEAM_LENGTH;
        }
        return (float) Math.max(1.0, hit.getLocation().distanceTo(origin));
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
                                 float beamSize, float focus, float length,
                                 float r, float g, float b, float alpha) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = poseStack.last().pose();
        float len = length;
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
