package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.CropWindow;
import dev.nano.ndidisplays.block.FloorScanner;
import dev.nano.ndidisplays.block.LedFloorBlockEntity;
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
 * Draws a walkable LED floor as a single horizontal quad from the anchor tile,
 * through the same LED-simulation shader as the walls. FACING is the image's
 * top (v=0); u runs clockwise of that when looking down.
 */
public class LedFloorRenderer implements BlockEntityRenderer<LedFloorBlockEntity> {

    private static final float THICKNESS = 2.0F / 16.0F;
    private static final float SURFACE_EPSILON = 0.002F;
    private static final float PIXEL_GAP = 0.15F;
    private static final float CALIBRATION_VARIANCE = 0.06F;

    @Override
    public void render(LedFloorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null || !be.isRenderAnchor()) {
            return;
        }
        FloorScanner.FloorInfo floor = be.getFloorInfo();
        ShaderInstance shader = ClientSetup.ledWallShader;
        if (floor == null || shader == null) {
            return;
        }

        int mode = be.getTestPattern();
        Vec3[] corners = corners(floor);
        Vec3 p00 = corners[0];
        Vec3 p10 = corners[1];
        Vec3 p11 = corners[2];
        Vec3 p01 = corners[3];
        Vec3 up = new Vec3(0, 1, 0);

        if (ShaderPackCompat.shaderPackActive()) {
            renderShaderPackCompat(be, floor, mode, p00, p10, p11, p01, up, poseStack, buffers);
            return;
        }

        int texId;
        ResourceLocation bloomTexture = null;
        if (mode == 0) {
            texId = ScreenVideo.textureId(be.getSourceName());
            bloomTexture = ScreenVideo.textureLocation(be.getSourceName());
        } else {
            texId = FallbackTextures.white();
        }

        int pxPerBlock = be.getPixelsPerBlock();
        float gridW = pxPerBlock * floor.width();
        float gridH = pxPerBlock * floor.depth();
        CropWindow crop = be.crop();

        shader.safeGetUniform("LedParams").set(gridW, gridH, ScreenVideo.ledGap(PIXEL_GAP), be.getEffectiveBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode, (float) pxPerBlock, ScreenVideo.ledVariance(CALIBRATION_VARIANCE));
        shader.safeGetUniform("UvRegion").set(crop.u0(), crop.v0(), crop.du(), crop.dv());

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        Matrix4f mat = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Vec3 rv = Vec3.atLowerCornerOf(FloorScanner.right(floor.facing()).getNormal());
        Vec3 fv = Vec3.atLowerCornerOf(floor.facing().getNormal());
        if (!floor.isShaped()) {
            vertex(builder, mat, p00, 0.0F, 1.0F);
            vertex(builder, mat, p10, 1.0F, 1.0F);
            vertex(builder, mat, p11, 1.0F, 0.0F);
            vertex(builder, mat, p01, 0.0F, 0.0F);
        } else {
            // One quad per run of tiles — the Eurovision-cross case. Frame = bounding box;
            // the build masks it.
            for (int[] run : FloorScanner.runs(floor)) {
                Vec3 bl = p00.add(rv.scale(run[0])).add(fv.scale(run[2]));
                Vec3 br = p00.add(rv.scale(run[1])).add(fv.scale(run[2]));
                float u0 = run[0] / (float) floor.width();
                float u1 = run[1] / (float) floor.width();
                float vB = 1.0F - run[2] / (float) floor.depth();
                float vT = 1.0F - (run[2] + 1) / (float) floor.depth();
                vertex(builder, mat, bl, u0, vB);
                vertex(builder, mat, br, u1, vB);
                vertex(builder, mat, br.add(fv), u1, vT);
                vertex(builder, mat, bl.add(fv), u0, vT);
            }
        }
        // Depth-bias the video face off its cabinet. The face sits a few millimetres proud of
        // the cabinet geometry, but depth precision falls with distance, so past ~60 blocks the
        // fixed offset drops below what the depth buffer can resolve and the face stipple-fights
        // the cabinet. Polygon offset biases in DEPTH-BUFFER units, so it scales with distance
        // automatically — same values vanilla's z-layering uses.
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        RenderSystem.enablePolygonOffset();
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();

        RenderSystem.enableCull();

        if (LedWallRenderer.SHIMMER_LOADED && !dev.nano.ndidisplays.client.CameraFeedManager.isCapturing()) {
            ResourceLocation shimmerTex = mode == 0 ? bloomTexture : FallbackTextures.whiteLocation();
            if (shimmerTex != null) {
                float[] bloomParams = new float[]{
                        gridW, gridH, ScreenVideo.ledGap(PIXEL_GAP), be.getEffectiveBrightness(),
                        be.getGamma(), (float) mode, (float) pxPerBlock, ScreenVideo.ledVariance(CALIBRATION_VARIANCE),
                        crop.u0(), crop.v0(), crop.du(), crop.dv()};
                if (!floor.isShaped()) {
                    ShimmerCompat.submitBloom(mat, p00, p10, p11, p01, shimmerTex, bloomParams);
                } else {
                    java.util.List<int[]> runs = FloorScanner.runs(floor);
                    if (runs.size() <= 128) {
                        for (int[] run : runs) {
                            Vec3 bl = p00.add(rv.scale(run[0])).add(fv.scale(run[2]));
                            Vec3 br = p00.add(rv.scale(run[1])).add(fv.scale(run[2]));
                            ShimmerCompat.submitBloomUv(mat, bl, br, br.add(fv), bl.add(fv),
                                    run[0] / (float) floor.width(), run[1] / (float) floor.width(),
                                    1.0F - run[2] / (float) floor.depth(),
                                    1.0F - (run[2] + 1) / (float) floor.depth(),
                                    shimmerTex, bloomParams);
                        }
                    }
                }
            }
        }
    }

    /**
     * Corners in local space of the anchor: p00 image bottom-left (u=0,v=1) through
     * p11 image top-right (u=1,v=0), looking down onto the floor.
     */
    private static Vec3[] corners(FloorScanner.FloorInfo floor) {
        Direction facing = floor.facing();
        Direction right = FloorScanner.right(facing);
        Vec3 r = Vec3.atLowerCornerOf(right.getNormal());
        Vec3 f = Vec3.atLowerCornerOf(facing.getNormal());
        float y = THICKNESS + SURFACE_EPSILON;
        Vec3 origin = new Vec3(0.5, y, 0.5)
                .subtract(r.scale(0.5))
                .subtract(f.scale(0.5))
                // Shaped floors: the render-anchor tile is not necessarily the box corner.
                .subtract(r.scale(floor.anchorAcross()))
                .subtract(f.scale(floor.anchorAlong()));
        Vec3 p00 = origin;
        Vec3 p10 = origin.add(r.scale(floor.width()));
        Vec3 p01 = origin.add(f.scale(floor.depth()));
        Vec3 p11 = p10.add(f.scale(floor.depth()));
        return new Vec3[]{p00, p10, p11, p01};
    }

    private void renderShaderPackCompat(LedFloorBlockEntity be, FloorScanner.FloorInfo floor, int mode,
                                        Vec3 p00, Vec3 p10, Vec3 p11, Vec3 p01, Vec3 normal,
                                        PoseStack poseStack, MultiBufferSource buffers) {
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
        CropWindow crop = be.crop();
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        if (!floor.isShaped()) {
            compatVertex(vc, mat, p00, crop.u0(), crop.v1(), normal, cr * bright, cg * bright, cb * bright);
            compatVertex(vc, mat, p10, crop.u1(), crop.v1(), normal, cr * bright, cg * bright, cb * bright);
            compatVertex(vc, mat, p11, crop.u1(), crop.v0(), normal, cr * bright, cg * bright, cb * bright);
            compatVertex(vc, mat, p01, crop.u0(), crop.v0(), normal, cr * bright, cg * bright, cb * bright);
        } else {
            Vec3 rv = Vec3.atLowerCornerOf(FloorScanner.right(floor.facing()).getNormal());
            Vec3 fv = Vec3.atLowerCornerOf(floor.facing().getNormal());
            for (int[] run : FloorScanner.runs(floor)) {
                Vec3 bl = p00.add(rv.scale(run[0])).add(fv.scale(run[2]));
                Vec3 br = p00.add(rv.scale(run[1])).add(fv.scale(run[2]));
                float u0 = crop.u0() + crop.du() * run[0] / (float) floor.width();
                float u1 = crop.u0() + crop.du() * run[1] / (float) floor.width();
                float vB = crop.v0() + crop.dv() * (1.0F - run[2] / (float) floor.depth());
                float vT = crop.v0() + crop.dv() * (1.0F - (run[2] + 1) / (float) floor.depth());
                compatVertex(vc, mat, bl, u0, vB, normal, cr * bright, cg * bright, cb * bright);
                compatVertex(vc, mat, br, u1, vB, normal, cr * bright, cg * bright, cb * bright);
                compatVertex(vc, mat, br.add(fv), u1, vT, normal, cr * bright, cg * bright, cb * bright);
                compatVertex(vc, mat, bl.add(fv), u0, vT, normal, cr * bright, cg * bright, cb * bright);
            }
        }
    }

    private static void compatVertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float u, float v,
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
    public boolean shouldRenderOffScreen(LedFloorBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
