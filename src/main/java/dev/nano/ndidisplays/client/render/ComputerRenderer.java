package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ComputerBlockEntity;
import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.client.computer.Computers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Draws a computer: tower, widescreen monitor and keyboard, with the machine's live desktop on
 * the panel. The desktop texture comes from {@link Computers} — the same pixels the NDI feed
 * carries, so the in-world monitor and the network output can never disagree.
 *
 * Also the machine's heartbeat: rendering checks the computer in with {@link Computers} (which
 * keeps the OS running) and with {@link CameraFeedManager} (which publishes it while the block
 * is broadcasting and this client is the broadcast host).
 */
public class ComputerRenderer implements BlockEntityRenderer<ComputerBlockEntity> {

    private static final ResourceLocation PARTS =
            new ResourceLocation(NdiDisplays.MODID, "textures/entity/camera_parts.png");

    // Tile indices into the 8x8 parts atlas, matching CameraRenderer's map.
    private static final int BODY = 0;
    private static final int BODY_LIGHT = 1;
    private static final int BLACK = 2;
    private static final int VENT = 5;
    private static final int SILVER = 6;
    private static final int LCD = 8;
    private static final int BLUE_LED = 12;
    private static final int TALLY = 4;

    @Override
    public void render(ComputerBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Computers.note(be);
        CameraFeedManager.noteComputer(be);

        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getFacing().toYRot()));

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(PARTS));
        // --- desk plate and tower off to the side ---
        box(pose, vc, packedLight, -0.44F, 0.0F, -0.28F, 0.44F, 0.04F, 0.30F, BODY);
        box(pose, vc, packedLight, 0.24F, 0.04F, -0.10F, 0.42F, 0.46F, 0.24F, BODY);
        box(pose, vc, packedLight, 0.245F, 0.08F, -0.11F, 0.415F, 0.40F, -0.10F, VENT);
        box(pose, vc, LightTexture.FULL_BRIGHT, 0.27F, 0.42F, -0.105F, 0.31F, 0.44F, -0.10F, BLUE_LED);
        if (be.isBroadcasting()) {
            box(pose, vc, LightTexture.FULL_BRIGHT,
                    0.35F, 0.42F, -0.105F, 0.39F, 0.44F, -0.10F, TALLY);
        }

        // --- keyboard in front of the monitor ---
        box(pose, vc, packedLight, -0.34F, 0.04F, -0.26F, 0.10F, 0.06F, -0.10F, SILVER);
        box(pose, vc, packedLight, -0.32F, 0.06F, -0.24F, 0.08F, 0.065F, -0.12F, BLACK);

        // --- monitor: stand + widescreen bezel ---
        box(pose, vc, packedLight, -0.16F, 0.04F, 0.10F, -0.08F, 0.24F, 0.16F, SILVER);
        box(pose, vc, packedLight, -0.55F, 0.24F, 0.10F, 0.31F, 0.78F, 0.15F, BLACK);
        pose.popPose();

        // --- the desktop on the panel ---
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getFacing().toYRot()));
        pose.translate(0.0, 0.0, 0.096);
        int tex = Computers.textureId(be.getBlockPos());
        if (tex != 0) {
            drawDesktop(pose, tex);
        } else {
            VertexConsumer off = buffers.getBuffer(RenderType.entityCutoutNoCull(PARTS));
            box(pose, off, packedLight, -0.53F, 0.26F, 0.0F, 0.29F, 0.76F, 0.004F, LCD);
        }
        pose.popPose();
    }

    /** Full-bright immediate quad on the machine's own framebuffer texture. */
    private static void drawDesktop(PoseStack pose, int tex) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        Matrix4f mat = pose.last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // A render target's texture is bottom-up: V 0 at the panel's bottom keeps it upright.
        // The monitor faces the block's front (-Z after the yaw), so X runs mirrored.
        b.vertex(mat, 0.29F, 0.26F, 0.0F).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, -0.53F, 0.26F, 0.0F).uv(0.0F, 0.0F).endVertex();
        b.vertex(mat, -0.53F, 0.76F, 0.0F).uv(0.0F, 1.0F).endVertex();
        b.vertex(mat, 0.29F, 0.76F, 0.0F).uv(1.0F, 1.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
        RenderSystem.enableCull();
    }

    /** Cuboid through the shared parts atlas; every face maps the whole 8x8 tile. */
    private static void box(PoseStack pose, VertexConsumer vc, int light,
                            float x0, float y0, float z0, float x1, float y1, float z1, int tile) {
        float u0 = ((tile % 8) * 8 + 0.5F) / 64.0F;
        float u1 = ((tile % 8) * 8 + 7.5F) / 64.0F;
        float v0 = ((tile / 8) * 8 + 0.5F) / 64.0F;
        float v1 = ((tile / 8) * 8 + 7.5F) / 64.0F;
        Matrix4f m = pose.last().pose();
        quad(vc, m, light, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, u0, v0, u1, v1, 0, 0, -1);
        quad(vc, m, light, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, u0, v0, u1, v1, 0, 0, 1);
        quad(vc, m, light, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, u0, v0, u1, v1, -1, 0, 0);
        quad(vc, m, light, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, u0, v0, u1, v1, 1, 0, 0);
        quad(vc, m, light, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, v0, u1, v1, 0, 1, 0);
        quad(vc, m, light, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, u0, v0, u1, v1, 0, -1, 0);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, int light,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1,
                             int nx, int ny, int nz) {
        put(vc, m, light, ax, ay, az, u0, v1, nx, ny, nz);
        put(vc, m, light, dx, dy, dz, u1, v1, nx, ny, nz);
        put(vc, m, light, cx, cy, cz, u1, v0, nx, ny, nz);
        put(vc, m, light, bx, by, bz, u0, v0, nx, ny, nz);
    }

    private static void put(VertexConsumer vc, Matrix4f m, int light,
                           float x, float y, float z, float u, float v, int nx, int ny, int nz) {
        vc.vertex(m, x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(nx, ny, nz)
                .endVertex();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRenderOffScreen(ComputerBlockEntity be) {
        // The machine keeps running — and its feed keeps going out — when nobody looks at it.
        return true;
    }
}
