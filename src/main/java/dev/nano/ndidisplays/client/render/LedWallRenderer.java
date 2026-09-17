package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.PanelFacing;
import dev.nano.ndidisplays.block.WallScanner;
import dev.nano.ndidisplays.client.ClientSetup;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;

/**
 * Draws the whole wall as a single quad from the anchor panel, through a custom
 * core shader that simulates real LED wall optics: per-LED point sampling of the
 * scaled video (mipmap LOD chosen like a real scaler), RGB subpixel stripes with
 * inter-pixel gap, per-LED calibration variance, panel gamma and brightness, and
 * a distance fade of the pixel structure so walls resolve into a smooth image at
 * audience distance without moiré.
 */
public class LedWallRenderer implements BlockEntityRenderer<LedPanelBlockEntity> {

    /** Cabinet depth in blocks (2/16 m, like a slim rental cabinet). */
    private static final float THICKNESS = 2.0F / 16.0F;
    /** Offset of the emissive surface in front of the cabinet face, avoids z-fighting. */
    private static final float SURFACE_EPSILON = 0.002F;
    /**
     * Quarter-arc tessellation, matching {@code CurvedScreenRenderer.SEGMENT_STEP} (5°).
     * 90° / 5° = 18 quads — a polygon at 6 facets still reads as a chamfer.
     */
    static final int ARC_DIV = Math.max(16, Math.round(90.0F / 5.0F));
    /** Fraction of each LED cell that is dark bezel on each side. */
    private static final float PIXEL_GAP = 0.15F;
    /** Per-LED brightness calibration spread (fraction, peak to peak). */
    private static final float CALIBRATION_VARIANCE = 0.06F;

    public static final boolean SHIMMER_LOADED = ModList.get().isLoaded("shimmer");

    @Override
    public void render(LedPanelBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null || !be.isRenderAnchor()) {
            return;
        }
        WallScanner.WallInfo wall = be.getWallInfo();
        boolean blowThrough = be.isBlowThrough();
        ShaderInstance shader = blowThrough ? ClientSetup.ledWallTransparentShader : ClientSetup.ledWallShader;
        if (wall == null || shader == null) {
            return;
        }

        int mode = be.getTestPattern();

        // Content-coloured light into the room. Before the shader-pack branch below so a wall
        // still lights the stage under a pack, where its own core shader cannot run.
        if (SHIMMER_LOADED) {
            ScreenLights.updateWall(be.getBlockPos(), wall, be.getSourceName(), mode,
                    be.crop(), be.getEffectiveBrightness());
        }

        // A shader pack (Iris/Oculus/OptiFine) replaces the world pipeline, and our own
        // core shader cannot participate in it — the wall would render black/invisible.
        // Fall back to a vanilla emissive RenderType the pack knows how to patch: the
        // video still plays, only the per-LED simulation is skipped.
        // Bends leave a wedge of air between the two cabinets' bodies; fill it like a mitred
        // cabinet would. Solid cabinets only — a blow-through wall is meant to be open.
        if (wall.isPath() && !blowThrough) {
            renderJoinFillers(wall, be.getBlockPos(), poseStack, buffers, packedLight);
        }

        if (ShaderPackCompat.shaderPackActive()) {
            renderShaderPackCompat(be, wall, mode, blowThrough, poseStack, buffers);
            return;
        }

        // A corner cabinet with no wall attached is an unmapped cabinet: dark, like the real
        // thing — not a one-column billboard of the entire frame.
        boolean unmappedCorner = wall.isPath() && wall.width() == 1
                && WallScanner.pathArc(wall, 0) != null;

        int texId;
        ResourceLocation bloomTexture = null;
        if (unmappedCorner) {
            texId = FallbackTextures.black();
        } else if (mode == 0) {
            texId = ScreenVideo.textureId(be.getSourceName());
            bloomTexture = ScreenVideo.textureLocation(be.getSourceName());
        } else {
            texId = FallbackTextures.white();
        }

        PanelFacing facing = wall.facing();
        int w = wall.width();
        int h = wall.height();
        int pxPerBlock = be.getPixelsPerBlock();
        // Pixels are counted per cabinet, not per block, so a diagonal wall's LED grid still
        // lands exactly on its cabinet seams — as on a real wall, where each cabinet holds a
        // whole number of LEDs. Diagonal cabinets are √2 blocks wide, so their pitch is
        // correspondingly coarser.
        float gridW = pxPerBlock * w;
        float gridH = pxPerBlock * h;

        Vec3 f = facing.normal();
        Vec3 r = facing.rightUnit();
        double pitch = facing.pitch();
        Vec3 base = new Vec3(0.5, 0.0, 0.5)
                .subtract(r.scale(pitch * 0.5))
                .add(f.scale(facing.surfaceOffset(THICKNESS, SURFACE_EPSILON)))
                // Shaped walls: the render-anchor tile (this block entity) is not necessarily the
                // bounding-box corner — a cross's bottom tile sits mid-box — so shift to the origin.
                .add(r.scale(-pitch * wall.anchorAcross()))
                .add(0.0, -wall.anchorUp(), 0.0);

        Vec3 span = r.scale(pitch * w);
        Vec3 p00 = base;                          // bottom, viewer-left
        Vec3 p10 = base.add(span);                // bottom, viewer-right
        Vec3 p11 = base.add(span).add(0, h, 0);   // top, viewer-right
        Vec3 p01 = base.add(0, h, 0);             // top, viewer-left

        shader.safeGetUniform("LedParams").set(gridW, gridH, ScreenVideo.ledGap(PIXEL_GAP), be.getEffectiveBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode, (float) pxPerBlock, ScreenVideo.ledVariance(CALIBRATION_VARIANCE));
        // The wall's input window (video-processor crop). Must be set every draw:
        // kinetic tiles reuse this uniform for their canvas slice.
        dev.nano.ndidisplays.block.CropWindow crop = be.crop();
        shader.safeGetUniform("UvRegion").set(crop.u0(), crop.v0(), crop.du(), crop.dv());

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        if (blowThrough) {
            // The shader discards the open area between emitters, so depth stays correct
            // and only the lit strips blend — the world behind shows through the gaps.
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        } else {
            RenderSystem.disableBlend();
        }
        RenderSystem.disableCull();

        Matrix4f mat = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        if (wall.isPath()) {
            // A bending wall: one vertical strip per column, positioned on the column's own face
            // segment, so a flat run, a 45° chamfer and the run beyond join corner-to-corner into
            // one continuous picture. Each column is one cabinet of the frame — bends never
            // stretch the image.
            emitPath(wall, be.getBlockPos(), (bl, br, tr, tl, u0, u1, vB, vT) -> {
                vertex(builder, mat, bl, u0, vB);
                vertex(builder, mat, br, u1, vB);
                vertex(builder, mat, tr, u1, vT);
                vertex(builder, mat, tl, u0, vT);
            });
        } else if (!wall.isShaped()) {
            vertex(builder, mat, p00, 0.0F, 1.0F);
            vertex(builder, mat, p10, 1.0F, 1.0F);
            vertex(builder, mat, p11, 1.0F, 0.0F);
            vertex(builder, mat, p01, 0.0F, 0.0F);
        } else {
            // One quad per horizontal run of tiles (per cabinet on a diagonal wall). The frame
            // is the shape's bounding box, so each run samples its own slice — a rectangular
            // source, masked by the build itself, which is exactly how real shaped LED (a
            // Eurovision cross) is driven.
            for (double[] run : faceRuns(wall)) {
                Vec3 bl = p00.add(r.scale(run[0])).add(0.0, run[2], 0.0);
                Vec3 br = p00.add(r.scale(run[1])).add(0.0, run[2], 0.0);
                float u0 = (float) run[3];
                float u1 = (float) run[4];
                float vB = 1.0F - (float) run[2] / h;
                float vT = 1.0F - ((float) run[2] + 1) / h;
                vertex(builder, mat, bl, u0, vB);
                vertex(builder, mat, br, u1, vB);
                vertex(builder, mat, br.add(0.0, 1.0, 0.0), u1, vT);
                vertex(builder, mat, bl.add(0.0, 1.0, 0.0), u0, vT);
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
        if (blowThrough) {
            RenderSystem.disableBlend();
        }

        // Shimmer's post targets are wired to the real main framebuffer; during a
        // camera-feed capture the main target is swapped out, so submitting bloom
        // would smear quads across the player's screen. Walls simply don't glow
        // on camera feeds.
        // Blow-through walls go through their own MRT variant, which discards the open gaps and
        // blends the strips by coverage, so only the lit strips glow.
        if (SHIMMER_LOADED && !dev.nano.ndidisplays.client.CameraFeedManager.isCapturing()) {
            // Re-draw the wall through Shimmer's post pass with the MRT variant of
            // the wall shader: identical pixels on screen, and the simulated LED
            // output lands in Shimmer's bloom buffer so the actual content glows.
            // A wall in video mode with no signal is black and casts no glow.
            ResourceLocation shimmerTex = mode == 0 ? bloomTexture : FallbackTextures.whiteLocation();
            if (shimmerTex != null) {
                float[] bloomParams = new float[]{
                        gridW, gridH, PIXEL_GAP, be.getEffectiveBrightness(),
                        be.getGamma(), (float) mode, (float) pxPerBlock, CALIBRATION_VARIANCE,
                        crop.u0(), crop.v0(), crop.du(), crop.dv()};
                if (wall.isPath()) {
                    // Same tessellated mesh as the colour pass, in ONE bloom submit. A chord
                    // quad on top of the arc read as a second screen; 18 separate submits
                    // froze the client.
                    ShimmerCompat.submitBloomMesh(mat, shimmerTex, bloomParams, blowThrough, (bmat, vc) ->
                            emitPath(wall, be.getBlockPos(), true, (bl, br, tr, tl, u0, u1, vB, vT) -> {
                                ShimmerCompat.vertex(vc, bmat, bl, u0, vB);
                                ShimmerCompat.vertex(vc, bmat, br, u1, vB);
                                ShimmerCompat.vertex(vc, bmat, tr, u1, vT);
                                ShimmerCompat.vertex(vc, bmat, tl, u0, vT);
                            }));
                } else if (!wall.isShaped()) {
                    ShimmerCompat.submitBloom(mat, p00, p10, p11, p01, shimmerTex, bloomParams, blowThrough);
                } else {
                    // Shaped: glow run by run so holes stay dark. Capped for pathological shapes
                    // (a checkerboard) — past the cap only the picture loses its bloom, not itself.
                    java.util.List<double[]> runs = faceRuns(wall);
                    if (runs.size() <= 128) {
                        for (double[] run : runs) {
                            Vec3 bl = p00.add(r.scale(run[0])).add(0.0, run[2], 0.0);
                            Vec3 br = p00.add(r.scale(run[1])).add(0.0, run[2], 0.0);
                            ShimmerCompat.submitBloomUv(mat, bl, br,
                                    br.add(0.0, 1.0, 0.0), bl.add(0.0, 1.0, 0.0),
                                    (float) run[3], (float) run[4],
                                    1.0F - (float) run[2] / h, 1.0F - ((float) run[2] + 1) / h,
                                    shimmerTex, bloomParams, blowThrough);
                        }
                    }
                }
            }
        }
    }

    /**
     * Shader-pack fallback: the wall as a flat emissive video surface via a vanilla
     * RenderType. No LED pixel structure, gamma or calibration variance — those live in
     * the core shader — but the feed itself shows correctly under any pack.
     */
    private void renderShaderPackCompat(LedPanelBlockEntity be, WallScanner.WallInfo wall, int mode,
                                        boolean blowThrough, PoseStack poseStack, MultiBufferSource buffers) {
        PanelFacing facing = wall.facing();
        int w = wall.width();
        int h = wall.height();
        Vec3 f = facing.normal();
        Vec3 r = facing.rightUnit();
        double pitch = facing.pitch();
        Vec3 base = new Vec3(0.5, 0.0, 0.5)
                .subtract(r.scale(pitch * 0.5))
                .add(f.scale(facing.surfaceOffset(THICKNESS, SURFACE_EPSILON)))
                .add(r.scale(-pitch * wall.anchorAcross()))
                .add(0.0, -wall.anchorUp(), 0.0);
        Vec3 span = r.scale(pitch * w);
        Vec3 p00 = base;
        Vec3 p10 = base.add(span);
        Vec3 p11 = base.add(span).add(0, h, 0);
        Vec3 p01 = base.add(0, h, 0);

        // Preferred: the baker pre-renders the full LED simulation into a texture outside
        // the pack's pipeline, so the wall keeps its real pixels under shaders. Brightness,
        // gamma and patterns are all baked in; the quad just displays it.
        ResourceLocation baked = ScreenVideo.suppressLive(be.getSourceName()) ? null : LedWallBaker.request(be);
        if (baked != null) {
            Matrix4f bakedMat = poseStack.last().pose();
            VertexConsumer bakedVc = buffers.getBuffer(RenderType.entityTranslucentEmissive(baked));
            // The bake writes bottom-up (v=1 at the wall's bottom edge), so sample flipped.
            if (wall.isPath()) {
                emitPath(wall, be.getBlockPos(), (bl, br, tr, tl, u0, u1, vB, vT) -> {
                    // baked v is inverted relative to the live shader's convention
                    Vec3 n = normalOf(bl, br);
                    compatVertex(bakedVc, bakedMat, bl, u0, 1.0F - vB, n, 1.0F, 1.0F, 1.0F, 1.0F);
                    compatVertex(bakedVc, bakedMat, br, u1, 1.0F - vB, n, 1.0F, 1.0F, 1.0F, 1.0F);
                    compatVertex(bakedVc, bakedMat, tr, u1, 1.0F - vT, n, 1.0F, 1.0F, 1.0F, 1.0F);
                    compatVertex(bakedVc, bakedMat, tl, u0, 1.0F - vT, n, 1.0F, 1.0F, 1.0F, 1.0F);
                });
            } else if (!wall.isShaped()) {
                compatVertex(bakedVc, bakedMat, p00, 0.0F, 0.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
                compatVertex(bakedVc, bakedMat, p10, 1.0F, 0.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
                compatVertex(bakedVc, bakedMat, p11, 1.0F, 1.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
                compatVertex(bakedVc, bakedMat, p01, 0.0F, 1.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                for (double[] run : faceRuns(wall)) {
                    Vec3 bl = p00.add(r.scale(run[0])).add(0.0, run[2], 0.0);
                    Vec3 br = p00.add(r.scale(run[1])).add(0.0, run[2], 0.0);
                    float u0 = (float) run[3];
                    float u1 = (float) run[4];
                    float vB = (float) run[2] / h;
                    float vT = ((float) run[2] + 1) / h;
                    compatVertex(bakedVc, bakedMat, bl, u0, vB, f, 1.0F, 1.0F, 1.0F, 1.0F);
                    compatVertex(bakedVc, bakedMat, br, u1, vB, f, 1.0F, 1.0F, 1.0F, 1.0F);
                    compatVertex(bakedVc, bakedMat, br.add(0.0, 1.0, 0.0), u1, vT, f, 1.0F, 1.0F, 1.0F, 1.0F);
                    compatVertex(bakedVc, bakedMat, bl.add(0.0, 1.0, 0.0), u0, vT, f, 1.0F, 1.0F, 1.0F, 1.0F);
                }
            }
            return;
        }

        // First frame (or bake failure): flat approximation until the bake lands.
        float bright = be.getEffectiveBrightness();
        float alpha = blowThrough ? 0.8F : 1.0F;
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
                cr = cg = cb = 0.02F; // no signal: screen reads dark
            }
        } else {
            tex = FallbackTextures.whiteLocation();
            switch (mode) {
                case 4 -> { cg = 0.0F; cb = 0.0F; }
                case 5 -> { cr = 0.0F; cb = 0.0F; }
                case 6 -> { cr = 0.0F; cg = 0.0F; }
                case 3 -> { }
                default -> { cr = cg = cb = 0.6F; } // grid/checker approximated as grey
            }
        }

        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        if (mode == 1 && !wall.isShaped()) {
            // Colour bars survive as real bars: eight vertical strips.
            float[][] bars = {
                    {1, 1, 1}, {1, 1, 0}, {0, 1, 1}, {0, 1, 0},
                    {1, 0, 1}, {1, 0, 0}, {0, 0, 1}, {0.05F, 0.05F, 0.05F}};
            for (int i = 0; i < bars.length; i++) {
                double t0 = i / 8.0;
                double t1 = (i + 1) / 8.0;
                compatQuad(vc, mat,
                        p00.lerp(p10, t0), p00.lerp(p10, t1), p01.lerp(p11, t1), p01.lerp(p11, t0), f,
                        bars[i][0] * 0.75F * bright, bars[i][1] * 0.75F * bright,
                        bars[i][2] * 0.75F * bright, alpha);
            }
        } else {
            // The wall's input window (video-processor crop) applied to the raw quad.
            dev.nano.ndidisplays.block.CropWindow crop = be.crop();
            if (mode == 1) {
                cr = cg = cb = 0.6F; // shaped bars approximated as grey, like grid/checker
            }
            if (wall.isPath()) {
                final float fcr = cr * bright;
                final float fcg = cg * bright;
                final float fcb = cb * bright;
                emitPath(wall, be.getBlockPos(), (bl, br, tr, tl, u0, u1, vB, vT) -> {
                    Vec3 n = normalOf(bl, br);
                    float cu0 = crop.u0() + crop.du() * u0;
                    float cu1 = crop.u0() + crop.du() * u1;
                    float cvB = crop.v0() + crop.dv() * vB;
                    float cvT = crop.v0() + crop.dv() * vT;
                    compatVertex(vc, mat, bl, cu0, cvB, n, fcr, fcg, fcb, alpha);
                    compatVertex(vc, mat, br, cu1, cvB, n, fcr, fcg, fcb, alpha);
                    compatVertex(vc, mat, tr, cu1, cvT, n, fcr, fcg, fcb, alpha);
                    compatVertex(vc, mat, tl, cu0, cvT, n, fcr, fcg, fcb, alpha);
                });
            } else if (!wall.isShaped()) {
                compatVertex(vc, mat, p00, crop.u0(), crop.v1(), f, cr * bright, cg * bright, cb * bright, alpha);
                compatVertex(vc, mat, p10, crop.u1(), crop.v1(), f, cr * bright, cg * bright, cb * bright, alpha);
                compatVertex(vc, mat, p11, crop.u1(), crop.v0(), f, cr * bright, cg * bright, cb * bright, alpha);
                compatVertex(vc, mat, p01, crop.u0(), crop.v0(), f, cr * bright, cg * bright, cb * bright, alpha);
            } else {
                for (double[] run : faceRuns(wall)) {
                    Vec3 bl = p00.add(r.scale(run[0])).add(0.0, run[2], 0.0);
                    Vec3 br = p00.add(r.scale(run[1])).add(0.0, run[2], 0.0);
                    float u0 = crop.u0() + crop.du() * (float) run[3];
                    float u1 = crop.u0() + crop.du() * (float) run[4];
                    float vB = crop.v0() + crop.dv() * (1.0F - (float) run[2] / h);
                    float vT = crop.v0() + crop.dv() * (1.0F - ((float) run[2] + 1) / h);
                    compatVertex(vc, mat, bl, u0, vB, f, cr * bright, cg * bright, cb * bright, alpha);
                    compatVertex(vc, mat, br, u1, vB, f, cr * bright, cg * bright, cb * bright, alpha);
                    compatVertex(vc, mat, br.add(0.0, 1.0, 0.0), u1, vT, f, cr * bright, cg * bright, cb * bright, alpha);
                    compatVertex(vc, mat, bl.add(0.0, 1.0, 0.0), u0, vT, f, cr * bright, cg * bright, cb * bright, alpha);
                }
            }
        }
    }

    private static void compatQuad(VertexConsumer vc, Matrix4f mat,
                                   Vec3 p00, Vec3 p10, Vec3 p11, Vec3 p01, Vec3 normal,
                                   float r, float g, float b, float a) {
        compatVertex(vc, mat, p00, 0.0F, 1.0F, normal, r, g, b, a);
        compatVertex(vc, mat, p10, 1.0F, 1.0F, normal, r, g, b, a);
        compatVertex(vc, mat, p11, 1.0F, 0.0F, normal, r, g, b, a);
        compatVertex(vc, mat, p01, 0.0F, 0.0F, normal, r, g, b, a);
    }

    private static void compatVertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float u, float v,
                                     Vec3 normal, float r, float g, float b, float a) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, a)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }

    /**
     * The horizontal runs a planar wall's picture is drawn in, as {a0, a1, y, u0, u1}: a0/a1 are
     * distances along the wall's right axis from its left edge: one quad per run of tiles,
     * scaled by the facing's stride (√2 on a diagonal wall, whose cabinets span their full
     * block diagonal and so meet corner to corner).
     */
    static java.util.List<double[]> faceRuns(WallScanner.WallInfo wall) {
        PanelFacing facing = wall.facing();
        int w = wall.width();
        java.util.List<double[]> out = new java.util.ArrayList<>();
        double pitch = facing.pitch();
        for (int[] run : WallScanner.runs(wall)) {
            out.add(new double[]{pitch * run[0], pitch * run[1], run[2],
                    run[0] / (double) w, run[1] / (double) w});
        }
        return out;
    }

    /** One quad per (column, vertical run) of a path wall, handed to {@code out}. */
    @FunctionalInterface
    interface PathQuad {
        void emit(Vec3 bl, Vec3 br, Vec3 tr, Vec3 tl, float u0, float u1, float vB, float vT);
    }

    /**
     * Walks a path wall's columns emitting one quad per vertical run, in the ANCHOR block
     * entity's local space (the pose stack is anchored there). Face segments are the scanner's
     * idealised polyline — cell-front edges and cell diagonals — which is what makes adjacent
     * columns of different orientation meet exactly at their shared corner.
     */
    static void emitPath(WallScanner.WallInfo wall, BlockPos anchorPos, PathQuad out) {
        emitPath(wall, anchorPos, true, out);
    }

    /**
     * The horizontal geometry of a path wall: per column, its face pushed out to the cabinet's
     * PHYSICAL screen plane as sub-segments {x0, z0, x1, z1, uFrac0, uFrac1}; per join between
     * consecutive columns (the last one wrapping to the first on a closed ring), the shared
     * idealised corner where the two cabinet bodies touch and each body's front depth there.
     */
    private static final class PathGeometry {
        final java.util.List<java.util.List<double[]>> columns = new java.util.ArrayList<>();
        /** {i, j, cornerX, cornerZ, nIx, nIz, frontI, nJx, nJz, frontJ, creased} */
        final java.util.List<double[]> joins = new java.util.ArrayList<>();
    }

    private static PathGeometry pathGeometry(WallScanner.WallInfo wall, boolean tessellateArcs) {
        int w = wall.width();
        PathGeometry geo = new PathGeometry();
        double[][] ideal = new double[w][];
        double[][] endNormals = new double[w][]; // {leftNx, leftNz, rightNx, rightNz}
        double[] fronts = new double[w];
        for (int i = 0; i < w; i++) {
            double[] seg = WallScanner.pathSegment(wall, i);
            double[] arc = WallScanner.pathArc(wall, i);
            ideal[i] = seg;
            PanelFacing facing = wall.pathFacings().get(i);
            // How far the cabinet body reaches in front of the scanner's idealised line: a flat
            // is a slab from its back edge forward, a chamfer a slab from its diagonal forward,
            // a corner's quarter-round has its front ON the arc. The face sits an epsilon ahead.
            double front = arc != null ? 0.0 : THICKNESS;
            fronts[i] = front;
            double off = front + SURFACE_EPSILON;
            java.util.List<double[]> subs = new java.util.ArrayList<>(ARC_DIV);
            if (arc == null || !tessellateArcs) {
                double dxs = seg[2] - seg[0];
                double dzs = seg[3] - seg[1];
                double len = Math.max(1.0E-6, Math.sqrt(dxs * dxs + dzs * dzs));
                double nx = -dzs / len;
                double nz = dxs / len;
                if (arc != null) {
                    // Untessellated corner (bloom fallback): the chord's normal must still point
                    // the way the arc bulges.
                    double mx = (seg[0] + seg[2]) * 0.5 - arc[0];
                    double mz = (seg[1] + seg[3]) * 0.5 - arc[1];
                    if ((nx * mx + nz * mz) * arc[2] < 0.0) {
                        nx = -nx;
                        nz = -nz;
                    }
                }
                subs.add(new double[]{seg[0] + nx * off, seg[1] + nz * off,
                        seg[2] + nx * off, seg[3] + nz * off, 0.0, 1.0});
                endNormals[i] = new double[]{nx, nz, nx, nz};
            } else {
                // Quarter arc, pushed out radially (outward for convex, inward for concave).
                // Endpoints are pinned to the scanner's so the arc meets its neighbours exactly.
                int div = ARC_DIV;
                double a0 = Math.atan2(seg[1] - arc[1], seg[0] - arc[0]);
                double a1 = Math.atan2(seg[3] - arc[1], seg[2] - arc[0]);
                double d = a1 - a0;
                while (d > Math.PI) {
                    d -= 2.0 * Math.PI;
                }
                while (d < -Math.PI) {
                    d += 2.0 * Math.PI;
                }
                double radius = 1.0 + arc[2] * off;
                for (int k = 0; k < div; k++) {
                    double t0 = a0 + d * k / div;
                    double t1 = a0 + d * (k + 1) / div;
                    double x0 = k == 0 ? radial(seg[0], arc[0], radius) : arc[0] + radius * Math.cos(t0);
                    double z0 = k == 0 ? radial(seg[1], arc[1], radius) : arc[1] + radius * Math.sin(t0);
                    double x1 = k == div - 1 ? radial(seg[2], arc[0], radius) : arc[0] + radius * Math.cos(t1);
                    double z1 = k == div - 1 ? radial(seg[3], arc[1], radius) : arc[1] + radius * Math.sin(t1);
                    subs.add(new double[]{x0, z0, x1, z1, k / (double) div, (k + 1) / (double) div});
                }
                endNormals[i] = new double[]{
                        arc[2] * (seg[0] - arc[0]), arc[2] * (seg[1] - arc[1]),
                        arc[2] * (seg[2] - arc[0]), arc[2] * (seg[3] - arc[1])};
            }
            geo.columns.add(subs);
        }
        for (int i = 0; i < w; i++) {
            int j = i + 1;
            if (j == w) {
                if (w < 3) {
                    break;
                }
                j = 0; // a closed ring: the last column joins the first
            }
            if (!samePoint(ideal[i][2], ideal[i][3], ideal[j][0], ideal[j][1])) {
                continue;
            }
            double[] join = {i, j, ideal[i][2], ideal[i][3],
                    endNormals[i][2], endNormals[i][3], fronts[i],
                    endNormals[j][0], endNormals[j][1], fronts[j], 0.0};
            // Two straight cabinets meeting at an angle: both faces end on the mitre point where
            // their offset planes intersect — one clean crease, like the mitred cabinets of a
            // real chamfered wall. Arcs keep a bridge instead: a 5° facet stretched to a mitre
            // would spike.
            double cross = join[4] * join[8] - join[5] * join[7];
            if (fronts[i] > 1.0E-6 && fronts[j] > 1.0E-6 && Math.abs(cross) > 0.05) {
                double[] m = mitre(join, SURFACE_EPSILON);
                java.util.List<double[]> a = geo.columns.get(i);
                double[] last = a.get(a.size() - 1);
                double[] first = geo.columns.get(j).get(0);
                last[2] = m[0];
                last[3] = m[1];
                first[0] = m[0];
                first[1] = m[1];
                join[10] = 1.0;
            }
            geo.joins.add(join);
        }
        return geo;
    }

    static void emitPath(WallScanner.WallInfo wall, BlockPos anchorPos, boolean tessellateArcs,
                         PathQuad out) {
        int w = wall.width();
        int h = wall.height();
        PathGeometry geo = pathGeometry(wall, tessellateArcs);
        for (int i = 0; i < w; i++) {
            for (double[] sub : geo.columns.get(i)) {
                double lx = sub[0] - anchorPos.getX();
                double lz = sub[1] - anchorPos.getZ();
                double rx = sub[2] - anchorPos.getX();
                double rz = sub[3] - anchorPos.getZ();
                float u0 = (float) ((i + sub[4]) / w);
                float u1 = (float) ((i + sub[5]) / w);
                int y = 0;
                while (y < h) {
                    if (!wall.has(i, y)) {
                        y++;
                        continue;
                    }
                    int y0 = y;
                    while (y < h && wall.has(i, y)) {
                        y++;
                    }
                    double yLo = y0 - wall.anchorUp();
                    double yHi = y - wall.anchorUp();
                    float vB = 1.0F - y0 / (float) h;
                    float vT = 1.0F - y / (float) h;
                    out.emit(new Vec3(lx, yLo, lz), new Vec3(rx, yLo, rz),
                            new Vec3(rx, yHi, rz), new Vec3(lx, yHi, lz), u0, u1, vB, vT);
                }
            }
        }
        // Bridge every bend. Each column's face ends where its own cabinet ends, so a flat
        // (face a full slab ahead of its back edge) and a chamfer (half a slab ahead of its
        // diagonal) leave a sliver between them at the shared corner. A short strip carrying the
        // boundary column's picture closes it, over the cabinet wedge emitJoinFillers draws.
        for (double[] join : geo.joins) {
            if (join[10] > 0.5) {
                continue; // creased: the faces already share their corner
            }
            int i = (int) join[0];
            int j = (int) join[1];
            double ax = join[2] + join[4] * (join[6] + SURFACE_EPSILON) - anchorPos.getX();
            double az = join[3] + join[5] * (join[6] + SURFACE_EPSILON) - anchorPos.getZ();
            double bx = join[2] + join[7] * (join[9] + SURFACE_EPSILON) - anchorPos.getX();
            double bz = join[3] + join[8] * (join[9] + SURFACE_EPSILON) - anchorPos.getZ();
            if (Math.abs(ax - bx) < 1.0E-6 && Math.abs(az - bz) < 1.0E-6) {
                continue; // same plane, same offset: the faces already meet
            }
            float u = (float) ((i + 1) / (double) w);
            int y = 0;
            while (y < h) {
                if (!wall.has(i, y) || !wall.has(j, y)) {
                    y++;
                    continue;
                }
                int y0 = y;
                while (y < h && wall.has(i, y) && wall.has(j, y)) {
                    y++;
                }
                double yLo = y0 - wall.anchorUp();
                double yHi = y - wall.anchorUp();
                float vB = 1.0F - y0 / (float) h;
                float vT = 1.0F - y / (float) h;
                out.emit(new Vec3(ax, yLo, az), new Vec3(bx, yLo, bz),
                        new Vec3(bx, yHi, bz), new Vec3(ax, yHi, az), u, u, vB, vT);
            }
        }
    }

    /** Cabinet-coloured filler geometry, handed out as quads (triangles repeat a vertex). */
    @FunctionalInterface
    interface FillerQuad {
        void emit(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal);
    }

    /**
     * The wedge of air between two cabinets that meet at a bend: their bodies touch only at the
     * shared back corner, so in front of it, between the flat's end face and the chamfer's, is a
     * gap the video bridge would otherwise float over. Fill it with a small prism — the mitre
     * a real chamfered wall's cabinets have — for every row both columns share.
     */
    static void emitJoinFillers(WallScanner.WallInfo wall, BlockPos anchorPos, FillerQuad out) {
        int h = wall.height();
        PathGeometry geo = pathGeometry(wall, true);
        for (double[] join : geo.joins) {
            int i = (int) join[0];
            int j = (int) join[1];
            if (join[6] < 1.0E-6 || join[9] < 1.0E-6) {
                continue; // one body has no depth in front of the corner: nothing to fill
            }
            double cx = join[2] - anchorPos.getX();
            double cz = join[3] - anchorPos.getZ();
            double ax = cx + join[4] * join[6];
            double az = cz + join[5] * join[6];
            double bx = cx + join[7] * join[9];
            double bz = cz + join[8] * join[9];
            double cross = (ax - cx) * (bz - cz) - (az - cz) * (bx - cx);
            if (Math.abs(cross) < 1.0E-6) {
                continue; // collinear: same plane, no wedge
            }
            // A creased join fills out to the bodies' own mitre point, so the cabinet wedge
            // reaches exactly the crease the video makes; an arc join fills the plain triangle.
            double mx;
            double mz;
            if (join[10] > 0.5) {
                double[] m = mitre(join, 0.0);
                mx = m[0] - anchorPos.getX();
                mz = m[1] - anchorPos.getZ();
            } else {
                mx = (ax + bx) * 0.5;
                mz = (az + bz) * 0.5;
            }
            Vec3 nA = new Vec3(join[4], 0.0, join[5]);
            Vec3 nB = new Vec3(join[7], 0.0, join[8]);
            int y = 0;
            while (y < h) {
                if (!wall.has(i, y) || !wall.has(j, y)) {
                    y++;
                    continue;
                }
                int y0 = y;
                while (y < h && wall.has(i, y) && wall.has(j, y)) {
                    y++;
                }
                double yLo = y0 - wall.anchorUp();
                double yHi = y - wall.anchorUp();
                Vec3 cLo = new Vec3(cx, yLo, cz);
                Vec3 aLo = new Vec3(ax, yLo, az);
                Vec3 mLo = new Vec3(mx, yLo, mz);
                Vec3 bLo = new Vec3(bx, yLo, bz);
                Vec3 cHi = new Vec3(cx, yHi, cz);
                Vec3 aHi = new Vec3(ax, yHi, az);
                Vec3 mHi = new Vec3(mx, yHi, mz);
                Vec3 bHi = new Vec3(bx, yHi, bz);
                out.emit(aLo, mLo, mHi, aHi, nA);                           // front, column i's plane
                out.emit(mLo, bLo, bHi, mHi, nB);                           // front, column j's plane
                out.emit(cHi, aHi, mHi, bHi, new Vec3(0.0, 1.0, 0.0));      // top cap
                out.emit(cLo, bLo, mLo, aLo, new Vec3(0.0, -1.0, 0.0));     // bottom cap
            }
        }
    }

    /** The cabinet side texture, as a plain file for an entity RenderType. */
    private static final ResourceLocation CABINET_SIDE =
            new ResourceLocation(dev.nano.ndidisplays.NdiDisplays.MODID, "textures/block/led_panel_side.png");

    /** Draws the bend fillers of a path wall through the world's buffers (any pipeline). */
    private static void renderJoinFillers(WallScanner.WallInfo wall, BlockPos anchorPos, PoseStack poseStack,
                                          MultiBufferSource buffers, int packedLight) {
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entitySolid(CABINET_SIDE));
        emitJoinFillers(wall, anchorPos, (a, b, c, d, n) -> {
            fillerVertex(vc, mat, a, 0.0F, 1.0F, n, packedLight);
            fillerVertex(vc, mat, b, 1.0F, 1.0F, n, packedLight);
            fillerVertex(vc, mat, c, 1.0F, 0.0F, n, packedLight);
            fillerVertex(vc, mat, d, 0.0F, 0.0F, n, packedLight);
        });
    }

    private static void fillerVertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float u, float v,
                                     Vec3 normal, int packedLight) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }

    /**
     * The point at which the two planes of a join meet, each pushed {@code extra} beyond its
     * body's front: solves n_i·d = f_i and n_j·d = f_j for d = P − corner.
     */
    private static double[] mitre(double[] join, double extra) {
        double fi = join[6] + extra;
        double fj = join[9] + extra;
        double det = join[4] * join[8] - join[5] * join[7];
        double dx = (fi * join[8] - fj * join[5]) / det;
        double dz = (join[4] * fj - join[7] * fi) / det;
        return new double[]{join[2] + dx, join[3] + dz};
    }

    /** One coordinate of a point on the unit circle about {@code centre}, rescaled to {@code radius}. */
    private static double radial(double p, double centre, double radius) {
        return centre + (p - centre) * radius;
    }

    private static boolean samePoint(double ax, double az, double bx, double bz) {
        return Math.abs(ax - bx) < 1.0E-4 && Math.abs(az - bz) < 1.0E-4;
    }

    private static Vec3 normalOf(Vec3 bl, Vec3 br) {
        Vec3 alongRight = br.subtract(bl).normalize();
        return new Vec3(-alongRight.z, 0.0, alongRight.x);
    }

    private static void vertex(BufferBuilder builder, Matrix4f mat, Vec3 pos, float u, float v) {
        builder.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .uv(u, v)
                .color(255, 255, 255, 255)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(LedPanelBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
