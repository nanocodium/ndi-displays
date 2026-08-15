package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.CurvedScreenBlockEntity;
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
 * Draws the curved LED screen: a vertical cylindrical arc centred on the mount
 * block, opening towards FACING. The source frame unrolls along the arc (u sweeps
 * the opening angle, v the height); 360 degrees wraps it into a seamless column.
 *
 * Concave shows the video on the inner face (readable from inside the horseshoe),
 * convex on the outer face — geometry is identical, only the u direction and the
 * cabinet side swap.
 */
public class CurvedScreenRenderer implements BlockEntityRenderer<CurvedScreenBlockEntity> {

    /** Cabinet depth behind the LED surface, blocks (radial). */
    private static final float THICKNESS = 0.12F;
    /** Target angular size of one segment, degrees. */
    private static final float SEGMENT_STEP = 5.0F;

    private static final float PIXEL_GAP = 0.15F;
    private static final float CALIBRATION_VARIANCE = 0.06F;

    @Override
    public void render(CurvedScreenBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }

        float r = be.getRadius();
        float h = be.getScreenHeight();
        double arc = Math.toRadians(be.getArcAngle());
        boolean fullCircle = be.getArcAngle() >= 359.5F;
        boolean convex = be.isConvex();
        int repeat = Math.max(1, be.getVideoRepeat());
        // Segment count is a multiple of the repeat count so every video copy starts
        // exactly on a quad boundary — no quad ever spans the seam between two copies.
        int segsPerCopy = Math.max((int) Math.ceil(8.0 / repeat),
                (int) Math.ceil(be.getArcAngle() / (SEGMENT_STEP * repeat)));
        int segments = segsPerCopy * repeat;

        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
        Vec3 center = new Vec3(0.5, 0.5, 0.5);

        float yBottom = (float) (0.5 - h * 0.5);
        float yTop = (float) (0.5 + h * 0.5);

        // Radial positions of the LED face and the cabinet back. Concave = video on the
        // inner face, cabinet radially outwards; convex = the opposite.
        float rFace = convex ? r + THICKNESS * 0.5F : r - THICKNESS * 0.5F;
        float rBack = convex ? r - THICKNESS * 0.5F : r + THICKNESS * 0.5F;

        Matrix4f mat = poseStack.last().pose();

        // --- Cabinet (buffered): back surface, top/bottom rings, end caps.
        VertexConsumer solid = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        for (int i = 0; i < segments; i++) {
            Vec3 d0 = arcDir(fwd, right, arc, i / (double) segments);
            Vec3 d1 = arcDir(fwd, right, arc, (i + 1) / (double) segments);
            Vec3 b0b = at(center, d0, rBack, yBottom);
            Vec3 b0t = at(center, d0, rBack, yTop);
            Vec3 b1b = at(center, d1, rBack, yBottom);
            Vec3 b1t = at(center, d1, rBack, yTop);
            Vec3 f0b = at(center, d0, rFace, yBottom);
            Vec3 f0t = at(center, d0, rFace, yTop);
            Vec3 f1b = at(center, d1, rFace, yBottom);
            Vec3 f1t = at(center, d1, rFace, yTop);
            litQuad(solid, mat, packedLight, 0.13F, b0t, b0b, b1b, b1t);
            litQuad(solid, mat, packedLight, 0.20F, f0t, f1t, b1t, b0t);
            litQuad(solid, mat, packedLight, 0.18F, f0b, b0b, b1b, f1b);
        }
        if (!fullCircle) {
            Vec3 dStart = arcDir(fwd, right, arc, 0);
            Vec3 dEnd = arcDir(fwd, right, arc, 1);
            litQuad(solid, mat, packedLight, 0.22F,
                    at(center, dStart, rFace, yTop), at(center, dStart, rFace, yBottom),
                    at(center, dStart, rBack, yBottom), at(center, dStart, rBack, yTop));
            litQuad(solid, mat, packedLight, 0.22F,
                    at(center, dEnd, rFace, yTop), at(center, dEnd, rFace, yBottom),
                    at(center, dEnd, rBack, yBottom), at(center, dEnd, rBack, yTop));
        }

        // --- LED surface.
        int mode = be.getTestPattern();

        // Content-coloured light into the room. Before the shader-pack branch below so a wall
        // still lights the stage under a pack, where its own core shader cannot run.
        if (LedWallRenderer.SHIMMER_LOADED) {
            ScreenLights.updateArc(be.getBlockPos(), center, fwd, right, arc, rFace,
                    yBottom, yTop, repeat, convex, be.getSourceName(), mode, be.crop(),
                    be.getEffectiveBrightness());
        }
        // The shader's LED grid lives in uv space (one copy = uv 0..1), so the pixel
        // count per copy keeps the physical pitch constant however many times it tiles.
        float gridW = (float) (be.getPixelsPerBlock() * arc * rFace / repeat);
        float gridH = be.getPixelsPerBlock() * h;

        if (ShaderPackCompat.shaderPackActive()) {
            renderShaderPackCompat(be, mode, center, fwd, right, arc, segments, repeat,
                    rFace, yBottom, yTop, convex, mat, buffers);
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

        shader.safeGetUniform("LedParams").set(gridW, gridH, PIXEL_GAP, be.getEffectiveBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode,
                (float) be.getPixelsPerBlock(), CALIBRATION_VARIANCE);
        // Input window (video-processor crop): vertex uv is 0..1 per tiled copy, and the
        // shader remaps it into this region — so every copy shows the cropped window.
        dev.nano.ndidisplays.block.CropWindow crop = be.crop();
        shader.safeGetUniform("UvRegion").set(crop.u0(), crop.v0(), crop.du(), crop.dv());

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < segments; i++) {
            double t0 = i / (double) segments;
            double t1 = (i + 1) / (double) segments;
            Vec3 d0 = arcDir(fwd, right, arc, t0);
            Vec3 d1 = arcDir(fwd, right, arc, t1);
            float[] uu = u(t0, t1, repeat, convex);
            vertex(builder, mat, at(center, d0, rFace, yTop), uu[0], 0.0F);
            vertex(builder, mat, at(center, d1, rFace, yTop), uu[1], 0.0F);
            vertex(builder, mat, at(center, d1, rFace, yBottom), uu[1], 1.0F);
            vertex(builder, mat, at(center, d0, rFace, yBottom), uu[0], 1.0F);
        }
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.enableCull();
    }

    /**
     * Direction from the block centre to the arc at parameter t in [0,1]. The arc is
     * symmetric about the FACING axis: t=0.5 points straight along FACING.
     */
    private static Vec3 arcDir(Vec3 fwd, Vec3 right, double arc, double t) {
        double theta = (t - 0.5) * arc;
        return fwd.scale(Math.cos(theta)).add(right.scale(Math.sin(theta)));
    }

    /**
     * u pair for one segment along the arc, so the image reads left-to-right for the
     * intended viewer: concave = standing at the centre looking out along FACING
     * (their left is the -right side, i.e. t=0); convex = standing outside looking
     * back, mirrored. The source tiles {@code repeat} times over the sweep — each
     * copy re-runs uv 0..1; segment boundaries are aligned on copy boundaries, so
     * both ends of a quad always belong to the same copy.
     */
    // Package-private so ScreenLights can light the arc from the same mapping the geometry
    // uses; two copies of this would drift and put a light on the wrong copy of the video.
    static float[] u(double t0, double t1, int repeat, boolean convex) {
        double tt0 = convex ? 1.0 - t0 : t0;
        double tt1 = convex ? 1.0 - t1 : t1;
        int copy = (int) Math.floor((tt0 + tt1) * 0.5 * repeat);
        return new float[]{
                (float) (tt0 * repeat - copy),
                (float) (tt1 * repeat - copy)};
    }

    private static Vec3 at(Vec3 center, Vec3 dir, float radius, float y) {
        return new Vec3(center.x + dir.x * radius, y, center.z + dir.z * radius);
    }

    private void renderShaderPackCompat(CurvedScreenBlockEntity be, int mode, Vec3 center,
                                        Vec3 fwd, Vec3 right, double arc, int segments, int repeat,
                                        float rFace, float yBottom, float yTop, boolean convex,
                                        Matrix4f mat, MultiBufferSource buffers) {
        float bright = be.getEffectiveBrightness();
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

        // Input window (video-processor crop) applied to the raw fallback arc.
        dev.nano.ndidisplays.block.CropWindow crop = be.crop();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        for (int i = 0; i < segments; i++) {
            double t0 = i / (double) segments;
            double t1 = (i + 1) / (double) segments;
            Vec3 d0 = arcDir(fwd, right, arc, t0);
            Vec3 d1 = arcDir(fwd, right, arc, t1);
            float[] uu = u(t0, t1, repeat, convex);
            float cu0 = crop.u0() + uu[0] * crop.du();
            float cu1 = crop.u0() + uu[1] * crop.du();
            float cvTop = crop.v0();
            float cvBottom = crop.v1();
            Vec3 normal = convex ? d0 : d0.scale(-1);
            emissiveVertex(vc, mat, at(center, d0, rFace, yTop), cu0, cvTop, normal,
                    cr * bright, cg * bright, cb * bright);
            emissiveVertex(vc, mat, at(center, d1, rFace, yTop), cu1, cvTop, normal,
                    cr * bright, cg * bright, cb * bright);
            emissiveVertex(vc, mat, at(center, d1, rFace, yBottom), cu1, cvBottom, normal,
                    cr * bright, cg * bright, cb * bright);
            emissiveVertex(vc, mat, at(center, d0, rFace, yBottom), cu0, cvBottom, normal,
                    cr * bright, cg * bright, cb * bright);
        }
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
    public boolean shouldRenderOffScreen(CurvedScreenBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
