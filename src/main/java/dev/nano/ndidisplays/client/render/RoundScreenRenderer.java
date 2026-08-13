package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.RoundScreenBlockEntity;
import dev.nano.ndidisplays.client.ClientSetup;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws the circular LED screen: an emissive video disc of the configured radius,
 * centred on the mount block and facing its FACING direction, with a dark cabinet
 * disc and rim band behind it.
 *
 * The disc samples the inscribed circle of the source frame (centre of the disc =
 * centre of the video), through the same LED-simulation shader as the rectangular
 * walls — so pitch, brightness, gamma and the test patterns behave identically.
 */
public class RoundScreenRenderer implements BlockEntityRenderer<RoundScreenBlockEntity> {

    /** Cabinet depth behind the LED surface, blocks. */
    private static final float THICKNESS = 0.12F;
    private static final float SURFACE_EPSILON = 0.004F;
    /** Triangle-fan resolution; 64 keeps a 16 m disc visibly round up close. */
    private static final int SEGMENTS = 64;

    private static final float PIXEL_GAP = 0.15F;
    private static final float CALIBRATION_VARIANCE = 0.06F;

    @Override
    public void render(RoundScreenBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }

        float r = be.getRadius();
        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 center = new Vec3(0.5, 0.5, 0.5);

        Vec3 faceCenter = center.add(fwd.scale(THICKNESS * 0.5 + SURFACE_EPSILON));
        Vec3 backCenter = center.subtract(fwd.scale(THICKNESS * 0.5));

        Matrix4f mat = poseStack.last().pose();

        // --- Cabinet: dark back disc and rim band through the buffered pipeline.
        VertexConsumer solid = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = (Math.PI * 2 * i) / SEGMENTS;
            double a1 = (Math.PI * 2 * (i + 1)) / SEGMENTS;
            Vec3 e0 = right.scale(Math.cos(a0) * r).add(up.scale(Math.sin(a0) * r));
            Vec3 e1 = right.scale(Math.cos(a1) * r).add(up.scale(Math.sin(a1) * r));
            // Back face (visible from behind the screen).
            litQuad(solid, mat, packedLight, 0.13F,
                    backCenter, backCenter.add(e0), backCenter.add(e1), backCenter);
            // Rim band between the front and back edges.
            litQuad(solid, mat, packedLight, 0.20F,
                    faceCenter.add(e0), backCenter.add(e0), backCenter.add(e1), faceCenter.add(e1));
        }

        // --- LED surface.
        int mode = be.getTestPattern();
        float grid = be.getPixelsPerBlock() * r * 2.0F;

        if (ShaderPackCompat.shaderPackActive()) {
            renderShaderPackCompat(be, mode, faceCenter, right, up, fwd, r, mat, buffers);
            return;
        }

        ShaderInstance shader = ClientSetup.ledWallShader;
        if (shader == null) {
            return;
        }

        int texId;
        if (mode == 0) {
            NdiStream stream = NdiManager.acquire(be.getSourceName());
            if (stream != null) {
                stream.uploadIfNeeded();
                texId = stream.getTextureId();
            } else {
                texId = 0;
            }
            if (texId == 0) {
                texId = FallbackTextures.black();
            }
        } else {
            texId = FallbackTextures.white();
        }

        shader.safeGetUniform("LedParams").set(grid, grid, PIXEL_GAP, be.getBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode,
                (float) be.getPixelsPerBlock(), CALIBRATION_VARIANCE);
        shader.safeGetUniform("UvRegion").set(0.0F, 0.0F, 1.0F, 1.0F);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = (Math.PI * 2 * i) / SEGMENTS;
            double a1 = (Math.PI * 2 * (i + 1)) / SEGMENTS;
            // The image's u axis runs viewer-left to viewer-right, which is the -right
            // direction for someone standing on the facing side (same convention as the
            // vertical winch tile); v=0 is the top of the frame.
            discVertex(builder, mat, faceCenter, right, up, r, a0);
            discVertex(builder, mat, faceCenter, right, up, r, a1);
            vertex(builder, mat, faceCenter, 0.5F, 0.5F);
            vertex(builder, mat, faceCenter, 0.5F, 0.5F);
        }
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.enableCull();
    }

    private static void discVertex(BufferBuilder builder, Matrix4f mat, Vec3 faceCenter,
                                   Vec3 right, Vec3 up, float r, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Vec3 pos = faceCenter.add(right.scale(cos * r)).add(up.scale(sin * r));
        vertex(builder, mat, pos, (float) (0.5 - 0.5 * cos), (float) (0.5 - 0.5 * sin));
    }

    /**
     * Shader-pack fallback: flat emissive video disc through a vanilla RenderType the
     * pack can patch — no LED structure, but the image survives any pack.
     */
    private void renderShaderPackCompat(RoundScreenBlockEntity be, int mode, Vec3 faceCenter,
                                        Vec3 right, Vec3 up, Vec3 normal, float r,
                                        Matrix4f mat, MultiBufferSource buffers) {
        float bright = be.getBrightness();
        ResourceLocation tex;
        float cr = 1.0F;
        float cg = 1.0F;
        float cb = 1.0F;
        if (mode == 0) {
            NdiStream stream = NdiManager.acquire(be.getSourceName());
            ResourceLocation video = null;
            if (stream != null) {
                stream.uploadIfNeeded();
                video = stream.getTextureLocation();
            }
            if (video != null) {
                tex = video;
            } else {
                tex = FallbackTextures.whiteLocation();
                cr = cg = cb = 0.02F;
            }
        } else {
            tex = FallbackTextures.whiteLocation();
            switch (mode) {
                case 4 -> { cg = 0.0F; cb = 0.0F; }
                case 5 -> { cr = 0.0F; cb = 0.0F; }
                case 6 -> { cr = 0.0F; cg = 0.0F; }
                case 3 -> { }
                default -> { cr = cg = cb = 0.6F; }
            }
        }

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = (Math.PI * 2 * i) / SEGMENTS;
            double a1 = (Math.PI * 2 * (i + 1)) / SEGMENTS;
            emissiveDiscVertex(vc, mat, faceCenter, right, up, r, a0, normal, cr * bright, cg * bright, cb * bright);
            emissiveDiscVertex(vc, mat, faceCenter, right, up, r, a1, normal, cr * bright, cg * bright, cb * bright);
            emissiveVertex(vc, mat, faceCenter, 0.5F, 0.5F, normal, cr * bright, cg * bright, cb * bright);
            emissiveVertex(vc, mat, faceCenter, 0.5F, 0.5F, normal, cr * bright, cg * bright, cb * bright);
        }
    }

    private static void emissiveDiscVertex(VertexConsumer vc, Matrix4f mat, Vec3 faceCenter,
                                           Vec3 right, Vec3 up, float r, double angle, Vec3 normal,
                                           float cr, float cg, float cb) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Vec3 pos = faceCenter.add(right.scale(cos * r)).add(up.scale(sin * r));
        emissiveVertex(vc, mat, pos, (float) (0.5 - 0.5 * cos), (float) (0.5 - 0.5 * sin),
                normal, cr, cg, cb);
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
                                       Vec3 normal, float r, float g, float b) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }

    private static void vertex(BufferBuilder builder, Matrix4f mat, Vec3 pos, float u, float v) {
        builder.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .uv(u, v)
                .color(255, 255, 255, 255)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(RoundScreenBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
