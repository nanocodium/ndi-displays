package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.client.ActiveCamClientState;
import dev.nano.ndidisplays.client.ActiveCamPilotMode;
import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.entity.ActiveCamGondolaEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public class ActiveCamGondolaRenderer extends EntityRenderer<ActiveCamGondolaEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(NdiDisplays.MODID, "textures/entity/camera_parts.png");

    private static final int BODY = 0;
    private static final int BLACK = 2;
    private static final int LENS = 3;
    private static final int TALLY = 4;
    private static final int SILVER = 6;
    private static final int GRIP = 7;

    public ActiveCamGondolaRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.25F;
    }

    @Override
    public void render(ActiveCamGondolaEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        if (CameraFeedManager.isCapturingActiveCam(entity.controllerPos())
                || ActiveCamPilotMode.isControlling(entity.controllerPos())) {
            return;
        }
        BlockPos ctrl = entity.controllerPos();
        ActiveCamPose cam = ActiveCamClientState.interpolated(ctrl, partialTick);
        if (cam == null) {
            cam = new ActiveCamPose(entity.position());
        }
        Vec3 entityPos = entity.getPosition(partialTick);
        pose.pushPose();
        pose.translate(cam.x - entityPos.x, cam.y - entityPos.y, cam.z - entityPos.z);

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        box(pose, vc, packedLight, -0.22F, -0.04F, -0.22F, 0.22F, 0.04F, 0.22F, BODY);
        box(pose, vc, packedLight, -0.20F, 0.04F, -0.20F, 0.20F, 0.08F, 0.20F, SILVER);
        for (int i = 0; i < 4; i++) {
            float a = (float) Math.toRadians(45 + i * 90);
            float ax = (float) Math.cos(a) * 0.22F;
            float az = (float) Math.sin(a) * 0.22F;
            box(pose, vc, packedLight, ax - 0.03F, 0.08F, az - 0.03F, ax + 0.03F, 0.14F, az + 0.03F, BLACK);
        }

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-cam.pan));
        pose.mulPose(Axis.XP.rotationDegrees(-cam.tilt));
        pose.mulPose(Axis.ZP.rotationDegrees(cam.roll));
        box(pose, vc, packedLight, -0.08F, -0.06F, -0.04F, 0.08F, 0.06F, 0.14F, GRIP);
        box(pose, vc, packedLight, -0.05F, -0.05F, 0.14F, 0.05F, 0.05F, 0.20F, LENS);
        var snap = ActiveCamClientState.latest(ctrl);
        if (snap != null && snap.live()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.03F, 0.06F, 0.02F, 0.03F, 0.09F, 0.08F, TALLY);
        }
        pose.popPose();
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(ActiveCamGondolaEntity entity) {
        return TEXTURE;
    }

    private static void box(PoseStack pose, VertexConsumer vc, int light,
                            float x0, float y0, float z0, float x1, float y1, float z1, int tile) {
        float u0 = ((tile % 8) * 8 + 0.5F) / 64.0F;
        float u1 = ((tile % 8) * 8 + 7.5F) / 64.0F;
        float v0 = ((tile / 8) * 8 + 0.5F) / 64.0F;
        float v1 = ((tile / 8) * 8 + 7.5F) / 64.0F;
        PoseStack.Pose p = pose.last();
        quad(p, vc, light, u0, v0, u1, v1, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0);
        quad(p, vc, light, u0, v0, u1, v1, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0);
        quad(p, vc, light, u0, v0, u1, v1, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0, 0, 0, -1);
        quad(p, vc, light, u0, v0, u1, v1, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0, 0, 1);
        quad(p, vc, light, u0, v0, u1, v1, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1, 0, 0);
        quad(p, vc, light, u0, v0, u1, v1, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, 1, 0, 0);
    }

    private static void quad(PoseStack.Pose p, VertexConsumer vc, int light,
                             float u0, float v0, float u1, float v1,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz) {
        vertex(p, vc, light, ax, ay, az, u0, v1, nx, ny, nz);
        vertex(p, vc, light, bx, by, bz, u1, v1, nx, ny, nz);
        vertex(p, vc, light, cx, cy, cz, u1, v0, nx, ny, nz);
        vertex(p, vc, light, dx, dy, dz, u0, v0, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer vc, int light,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz) {
        vc.vertex(p.pose(), x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(p.normal(), nx, ny, nz)
                .endVertex();
    }
}
