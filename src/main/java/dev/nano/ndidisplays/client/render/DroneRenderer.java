package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.entity.DroneEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Compact cine quadcopter: graphite body, four arms, spinning rotors, and a
 * gimballed lens that matches the NDI / FPV pose.
 */
public class DroneRenderer extends EntityRenderer<DroneEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(NdiDisplays.MODID, "textures/entity/camera_parts.png");

    private static final int BODY = 0;
    private static final int BLACK = 2;
    private static final int LENS = 3;
    private static final int TALLY = 4;
    private static final int SILVER = 6;
    private static final int GRIP = 7;
    private static final int ORANGE = 14;
    private static final int PTZ_GLOSS = 22;
    private static final int PTZ_RING = 19;

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.35F;
    }

    @Override
    public void render(DroneEntity drone, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        if (CameraFeedManager.isCapturingDrone(drone)) {
            return;
        }
        super.render(drone, yaw, partialTick, pose, buffers, packedLight);
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        float heading = drone.heading(partialTick);
        float pitch = drone.gimbalPitch(partialTick);
        float spin = drone.isFlying() ? (drone.tickCount + partialTick) * 42.0F : 0.0F;

        pose.pushPose();
        pose.translate(0.0, 0.10, 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(-heading));

        box(pose, vc, packedLight, -0.11F, -0.04F, -0.09F, 0.11F, 0.05F, 0.09F, PTZ_GLOSS);
        box(pose, vc, packedLight, -0.07F, 0.05F, -0.05F, 0.07F, 0.07F, 0.05F, BLACK);
        box(pose, vc, packedLight, -0.02F, 0.07F, -0.02F, 0.02F, 0.09F, 0.02F, SILVER);

        for (int i = 0; i < 4; i++) {
            float a = (float) Math.toRadians(45 + i * 90);
            float ax = (float) Math.cos(a);
            float az = (float) Math.sin(a);
            box(pose, vc, packedLight,
                    ax * 0.08F - 0.015F, -0.015F, az * 0.08F - 0.015F,
                    ax * 0.22F + 0.015F, 0.015F, az * 0.22F + 0.015F, BODY);
            pose.pushPose();
            pose.translate(ax * 0.24F, 0.02F, az * 0.24F);
            pose.mulPose(Axis.YP.rotationDegrees(spin + i * 20.0F));
            box(pose, vc, packedLight, -0.11F, 0.00F, -0.012F, 0.11F, 0.008F, 0.012F, BLACK);
            box(pose, vc, packedLight, -0.012F, 0.00F, -0.11F, 0.012F, 0.008F, 0.11F, BLACK);
            box(pose, vc, packedLight, -0.018F, -0.01F, -0.018F, 0.018F, 0.016F, 0.018F, SILVER);
            pose.popPose();
        }

        pose.pushPose();
        pose.translate(0.0F, -0.02F, 0.10F);
        pose.mulPose(Axis.XP.rotationDegrees(-pitch));
        box(pose, vc, packedLight, -0.035F, -0.03F, -0.02F, 0.035F, 0.03F, 0.06F, GRIP);
        box(pose, vc, packedLight, -0.028F, -0.024F, 0.06F, 0.028F, 0.024F, 0.10F, BLACK);
        box(pose, vc, packedLight, -0.022F, -0.018F, 0.10F, 0.022F, 0.018F, 0.112F, LENS);
        if (drone.isLive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.012F, 0.03F, 0.00F, 0.012F, 0.04F, 0.03F, TALLY);
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.03F, -0.026F, 0.098F, 0.03F, 0.026F, 0.102F, PTZ_RING);
        }
        pose.popPose();

        if (drone.path().isPlaying()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, 0.04F, 0.05F, -0.04F, 0.07F, 0.07F, -0.01F, ORANGE);
        }
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity drone) {
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
