package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.SphereScreenBlockEntity;
import dev.nano.ndidisplays.client.ClientSetup;
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
 * Draws the spherical LED screen: an emissive video globe of the configured diameter,
 * centred on the mount block. The source frame is wrapped as an equirectangular map —
 * u runs once around the equator, v from the north pole to the south — with the centre of
 * the frame on the block's FACING side, so the seam sits round the back.
 *
 * Same LED-simulation shader as the walls. The LED grid is the globe's unrolled surface:
 * circumference × half-circumference at the configured pitch.
 */
public class SphereScreenRenderer implements BlockEntityRenderer<SphereScreenBlockEntity> {

    private static final float PIXEL_GAP = 0.15F;
    private static final float CALIBRATION_VARIANCE = 0.06F;

    /** A vertex of the globe: position in block-local space plus its map coordinates. */
    private interface SphereVertex {
        void emit(Vec3 pos, Vec3 normal, float u, float v);
    }

    @Override
    public void render(SphereScreenBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        float r = be.getRadius();
        int mode = be.getTestPattern();
        Matrix4f mat = poseStack.last().pose();

        if (ShaderPackCompat.shaderPackActive()) {
            renderShaderPackCompat(be, mode, r, mat, buffers);
            return;
        }

        ShaderInstance shader = ClientSetup.ledWallShader;
        if (shader == null) {
            return;
        }

        int texId = mode == 0 ? ScreenVideo.textureId(be.getSourceName()) : FallbackTextures.white();

        float circumference = (float) (Math.PI * 2.0 * r);
        float gridW = be.getPixelsPerBlock() * circumference;
        float gridH = gridW * 0.5F;
        shader.safeGetUniform("LedParams").set(gridW, gridH, ScreenVideo.ledGap(PIXEL_GAP), be.getEffectiveBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode,
                (float) be.getPixelsPerBlock(), ScreenVideo.ledVariance(CALIBRATION_VARIANCE));
        dev.nano.ndidisplays.block.CropWindow crop = be.crop();
        shader.safeGetUniform("UvRegion").set(crop.u0(), crop.v0(), crop.du(), crop.dv());

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Both sides: from inside a big globe the picture is still there (mirrored).
        RenderSystem.disableCull();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        tessellate(be, r, (pos, n, u, v) -> builder
                .vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .uv(u, v)
                .color(255, 255, 255, 255)
                .endVertex());
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.enableCull();
    }

    /**
     * Shader-pack fallback: the globe as a plain emissive video surface through a vanilla
     * RenderType the pack can patch — no LED structure, but the image survives any pack.
     */
    private void renderShaderPackCompat(SphereScreenBlockEntity be, int mode, float r,
                                        Matrix4f mat, MultiBufferSource buffers) {
        float bright = be.getEffectiveBrightness();
        ResourceLocation tex;
        float cr = 1.0F;
        float cg = 1.0F;
        float cb = 1.0F;
        if (mode == 0) {
            ResourceLocation video = ScreenVideo.textureLocation(be.getSourceName());
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
        final float fr = cr * bright;
        final float fg = cg * bright;
        final float fb = cb * bright;
        dev.nano.ndidisplays.block.CropWindow crop = be.crop();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        tessellate(be, r, (pos, n, u, v) -> vc
                .vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(fr, fg, fb, 1.0F)
                .uv(crop.u0() + crop.du() * u, crop.v0() + crop.dv() * v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) n.x, (float) n.y, (float) n.z)
                .endVertex());
    }

    /**
     * Emits the globe as quads (lat/long grid) in block-local space, four vertices per cell.
     * Resolution follows the diameter so a 32 m globe stays round up close without a 0.5 m
     * one burning thousands of quads.
     */
    private static void tessellate(SphereScreenBlockEntity be, float r, SphereVertex out) {
        int slices = Math.max(32, Math.min(128, Math.round(r * 12.0F)));
        int stacks = slices / 2;
        Vec3 centre = new Vec3(0.5, 0.5, 0.5);
        Direction facing = be.getFacing();
        // Longitude 0 (the frame's centre, u = 0.5) points along FACING; u increases toward
        // the viewer's right when standing on that side and looking at the globe.
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getCounterClockWise().getNormal());
        for (int j = 0; j < stacks; j++) {
            double phi0 = Math.PI * j / stacks;        // 0 at the north pole
            double phi1 = Math.PI * (j + 1) / stacks;
            float v0 = (float) j / stacks;
            float v1 = (float) (j + 1) / stacks;
            for (int i = 0; i < slices; i++) {
                double th0 = Math.PI * 2.0 * i / slices - Math.PI;   // -π..π, 0 = facing
                double th1 = Math.PI * 2.0 * (i + 1) / slices - Math.PI;
                float u0 = (float) i / slices;
                float u1 = (float) (i + 1) / slices;
                Vec3 n00 = normal(fwd, right, th0, phi0);
                Vec3 n10 = normal(fwd, right, th1, phi0);
                Vec3 n11 = normal(fwd, right, th1, phi1);
                Vec3 n01 = normal(fwd, right, th0, phi1);
                out.emit(centre.add(n01.scale(r)), n01, u0, v1);
                out.emit(centre.add(n11.scale(r)), n11, u1, v1);
                out.emit(centre.add(n10.scale(r)), n10, u1, v0);
                out.emit(centre.add(n00.scale(r)), n00, u0, v0);
            }
        }
    }

    /** Unit vector at longitude {@code theta} (0 along fwd) and colatitude {@code phi}. */
    private static Vec3 normal(Vec3 fwd, Vec3 right, double theta, double phi) {
        double s = Math.sin(phi);
        return fwd.scale(Math.cos(theta) * s)
                .add(right.scale(Math.sin(theta) * s))
                .add(0.0, Math.cos(phi), 0.0);
    }

    @Override
    public boolean shouldRenderOffScreen(SphereScreenBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
