package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nano.ndidisplays.activecam.ActiveCamBounds;
import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import dev.nano.ndidisplays.block.CameraWinchBlockEntity;
import dev.nano.ndidisplays.client.ActiveCamClientState;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Drum is the baked block model; this BER only draws the cable to the gondola. */
public class CameraWinchRenderer implements BlockEntityRenderer<CameraWinchBlockEntity> {

    private static final float CABLE_HALF = 0.012F;

    public CameraWinchRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(CameraWinchBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockPos ctrlPos = be.getControllerPos();
        if (ctrlPos == null || be.getLevel() == null) {
            return;
        }
        if (!(be.getLevel().getBlockEntity(ctrlPos) instanceof ActiveCamControllerBlockEntity)) {
            return;
        }
        ActiveCamPose pose = ActiveCamClientState.interpolated(ctrlPos, partialTick);
        if (pose == null) {
            return;
        }
        Vec3 drum = ActiveCamBounds.drumOf(be.getBlockPos());
        Vec3 attach = gondolaAttach(pose.position(), drum);
        Vec3 origin = Vec3.atLowerCornerOf(be.getBlockPos());
        Vec3 from = drum.subtract(origin);
        Vec3 to = attach.subtract(origin);
        Vec3 dir = to.subtract(from);
        if (dir.lengthSqr() < 1.0e-6) {
            return;
        }
        Vec3 n = dir.normalize();
        Vec3 right = Math.abs(n.y) > 0.95 ? new Vec3(1, 0, 0).cross(n).normalize()
                : new Vec3(0, 1, 0).cross(n).normalize();
        Vec3 fwd = n.cross(right).normalize();
        VertexConsumer vc = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        Matrix4f mat = poseStack.last().pose();
        cableQuad(vc, mat, from, to, right.scale(CABLE_HALF), packedLight);
        cableQuad(vc, mat, from, to, fwd.scale(CABLE_HALF), packedLight);
    }

    private static Vec3 gondolaAttach(Vec3 gondola, Vec3 drum) {
        Vec3 d = drum.subtract(gondola);
        Vec3 horiz = new Vec3(d.x, 0, d.z);
        if (horiz.lengthSqr() < 1.0e-4) {
            return gondola.add(0, 0.12, 0);
        }
        return gondola.add(horiz.normalize().scale(0.22)).add(0, 0.12, 0);
    }

    private static void cableQuad(VertexConsumer vc, Matrix4f mat, Vec3 a, Vec3 b, Vec3 half, int light) {
        float s = 0.16F;
        lit(vc, mat, a.subtract(half), s, light);
        lit(vc, mat, a.add(half), s, light);
        lit(vc, mat, b.add(half), s, light);
        lit(vc, mat, b.subtract(half), s, light);
        lit(vc, mat, b.subtract(half), s, light);
        lit(vc, mat, b.add(half), s, light);
        lit(vc, mat, a.add(half), s, light);
        lit(vc, mat, a.subtract(half), s, light);
    }

    private static void lit(VertexConsumer vc, Matrix4f mat, Vec3 p, float shade, int light) {
        int c = (int) (shade * 255);
        vc.vertex(mat, (float) p.x, (float) p.y, (float) p.z)
                .color(c, c, c, 255)
                .uv(0.5F, 0.5F)
                .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .uv2(light == 0 ? LightTexture.FULL_BRIGHT : light)
                .normal(0, 1, 0)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(CameraWinchBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
