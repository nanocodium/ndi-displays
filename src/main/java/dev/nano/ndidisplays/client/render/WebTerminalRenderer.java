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
import dev.nano.ndidisplays.block.WebTerminalBlockEntity;
import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.client.web.WebBrowsers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws a web terminal: a desk chassis with a monitor whose panel shows the live page.
 *
 * The page is a raw GL texture owned by Chromium, so it is drawn with an immediate-mode quad
 * bound directly to that id rather than through a RenderType — there is no ResourceLocation to
 * give the batching system, and going via the TextureManager would mean re-registering every time
 * Chromium reallocates. The chassis is drawn normally through the shared parts atlas.
 *
 * This renderer is also what keeps the terminal's browser alive: creating browsers lazily, from
 * the thing that actually needs pixels, is what stops a stage full of terminals each starting a
 * Chromium process the moment the chunk loads.
 */
public class WebTerminalRenderer implements BlockEntityRenderer<WebTerminalBlockEntity> {

    private static final ResourceLocation PARTS =
            new ResourceLocation(NdiDisplays.MODID, "textures/entity/camera_parts.png");

    // Tile indices into the 8x8 parts atlas, matching CameraRenderer's map.
    private static final int BODY = 0;
    private static final int BODY_LIGHT = 1;
    private static final int BLACK = 2;
    private static final int VENT = 5;
    private static final int SILVER = 6;
    private static final int LCD = 8;
    private static final int CARBON = 10;
    private static final int BLUE_LED = 12;
    private static final int TALLY = 4;

    /** Panel inset from the bezel, blocks. */
    private static final float SCREEN_Z = 0.5F / 16.0F;

    // The workstation mesh's screen_panel bounds, block-local (facing=north, unrotated).
    private static final float SCR_X0 = 0.114F;
    private static final float SCR_X1 = 0.586F;
    private static final float SCR_Y0 = 0.164F;
    private static final float SCR_Y1 = 0.436F;
    private static final float SCR_Z = 0.6505F + 0.004F;

    @Override
    public void render(WebTerminalBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        // Registering here rather than on block load means only terminals someone can actually
        // see get published, and the NDI sender follows the browser rather than the chunk.
        CameraFeedManager.noteWebTerminal(be);

        WebBrowsers.Session session = WebBrowsers.session(be.getBlockPos(), be.getUrl(),
                be.getWidth(), be.getHeight(),
                be.getLevel() == null ? 0L : be.getLevel().getGameTime());
        int tex = session == null ? 0 : session.textureId();
        if (tex == 0) {
            return; // the mesh's own dark panel reads as a switched-off monitor
        }
        // The chassis is the block model (rotated by the blockstate); this pass adds only the
        // live page, on the mesh's screen panel, through the same rotation.
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(be.getFacing().toYRot() + 180.0F)));
        pose.translate(-0.5, 0.0, -0.5);
        drawPage(pose, tex);
        pose.popPose();
    }

    /**
     * Immediate-mode quad bound straight to Chromium's texture, full-bright: a monitor emits its
     * own light. CEF's image is top-down, so V is 1 at the panel's bottom.
     */
    private static void drawPage(PoseStack pose, int tex) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        Matrix4f mat = pose.last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        vertex(b, mat, SCR_X0, SCR_Y0, SCR_Z, 0.0F, 1.0F);
        vertex(b, mat, SCR_X1, SCR_Y0, SCR_Z, 1.0F, 1.0F);
        vertex(b, mat, SCR_X1, SCR_Y1, SCR_Z, 1.0F, 0.0F);
        vertex(b, mat, SCR_X0, SCR_Y1, SCR_Z, 0.0F, 0.0F);
        BufferUploader.drawWithShader(b.end());
        RenderSystem.enableCull();
    }

    private static void vertex(BufferBuilder b, Matrix4f mat, float x, float y, float z,
                               float u, float v) {
        b.vertex(mat, x, y, z).uv(u, v).endVertex();
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

    /** Terminals are worth drawing from a distance: they are signage as much as computers. */
    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRenderOffScreen(WebTerminalBlockEntity be) {
        // The browser must keep painting even when the block is off screen, or the NDI feed
        // stalls the moment the operator looks away from the terminal.
        return true;
    }

    /** Unused, but part of the interface contract for renderers that inspect positions. */
    protected static Vec3 unused() {
        return Vec3.ZERO;
    }
}
