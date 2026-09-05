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
            // One quad per horizontal run of tiles. The frame is the shape's bounding box, so
            // each run samples its own slice — a rectangular source, masked by the build itself,
            // which is exactly how real shaped LED (a Eurovision cross) is driven.
            for (int[] run : WallScanner.runs(wall)) {
                Vec3 bl = p00.add(r.scale(pitch * run[0])).add(0.0, run[2], 0.0);
                Vec3 br = p00.add(r.scale(pitch * run[1])).add(0.0, run[2], 0.0);
                float u0 = run[0] / (float) w;
                float u1 = run[1] / (float) w;
                float vB = 1.0F - run[2] / (float) h;
                float vT = 1.0F - (run[2] + 1) / (float) h;
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
        // Blow-through walls are skipped: the MRT bloom variant is the opaque shader, so it
        // would stamp a solid quad into the bloom buffer and glow the open gaps too.
        if (SHIMMER_LOADED && !blowThrough && !dev.nano.ndidisplays.client.CameraFeedManager.isCapturing()) {
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
                    ShimmerCompat.submitBloomMesh(mat, shimmerTex, bloomParams, (bmat, vc) ->
                            emitPath(wall, be.getBlockPos(), true, (bl, br, tr, tl, u0, u1, vB, vT) -> {
                                ShimmerCompat.vertex(vc, bmat, bl, u0, vB);
                                ShimmerCompat.vertex(vc, bmat, br, u1, vB);
                                ShimmerCompat.vertex(vc, bmat, tr, u1, vT);
                                ShimmerCompat.vertex(vc, bmat, tl, u0, vT);
                            }));
                } else if (!wall.isShaped()) {
                    ShimmerCompat.submitBloom(mat, p00, p10, p11, p01, shimmerTex, bloomParams);
                } else {
                    // Shaped: glow run by run so holes stay dark. Capped for pathological shapes
                    // (a checkerboard) — past the cap only the picture loses its bloom, not itself.
                    java.util.List<int[]> runs = WallScanner.runs(wall);
                    if (runs.size() <= 128) {
                        for (int[] run : runs) {
                            Vec3 bl = p00.add(r.scale(pitch * run[0])).add(0.0, run[2], 0.0);
                            Vec3 br = p00.add(r.scale(pitch * run[1])).add(0.0, run[2], 0.0);
                            ShimmerCompat.submitBloomUv(mat, bl, br,
                                    br.add(0.0, 1.0, 0.0), bl.add(0.0, 1.0, 0.0),
                                    run[0] / (float) w, run[1] / (float) w,
                                    1.0F - run[2] / (float) h, 1.0F - (run[2] + 1) / (float) h,
                                    shimmerTex, bloomParams);
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
                for (int[] run : WallScanner.runs(wall)) {
                    Vec3 bl = p00.add(r.scale(pitch * run[0])).add(0.0, run[2], 0.0);
                    Vec3 br = p00.add(r.scale(pitch * run[1])).add(0.0, run[2], 0.0);
                    float u0 = run[0] / (float) w;
                    float u1 = run[1] / (float) w;
                    float vB = run[2] / (float) h;
                    float vT = (run[2] + 1) / (float) h;
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
        float alpha = blowThrough ? 0.55F : 1.0F;
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
                for (int[] run : WallScanner.runs(wall)) {
                    Vec3 bl = p00.add(r.scale(pitch * run[0])).add(0.0, run[2], 0.0);
                    Vec3 br = p00.add(r.scale(pitch * run[1])).add(0.0, run[2], 0.0);
                    float u0 = crop.u0() + crop.du() * run[0] / (float) w;
                    float u1 = crop.u0() + crop.du() * run[1] / (float) w;
                    float vB = crop.v0() + crop.dv() * (1.0F - run[2] / (float) h);
                    float vT = crop.v0() + crop.dv() * (1.0F - (run[2] + 1) / (float) h);
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

    static void emitPath(WallScanner.WallInfo wall, BlockPos anchorPos, boolean tessellateArcs,
                         PathQuad out) {
        int w = wall.width();
        int h = wall.height();
        for (int i = 0; i < w; i++) {
            double[] seg = WallScanner.pathSegment(wall, i);
            double[] arc = WallScanner.pathArc(wall, i);
            // Horizontal subsegments {x0, z0, x1, z1, uFrac0, uFrac1}: one for a straight
            // cabinet, ARC_DIV (~5°) around a corner cabinet's quarter-arc.
            java.util.List<double[]> subs = new java.util.ArrayList<>(ARC_DIV);
            if (arc == null || !tessellateArcs) {
                subs.add(new double[]{seg[0], seg[1], seg[2], seg[3], 0.0, 1.0});
            } else {
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
                for (int k = 0; k < div; k++) {
                    double t0 = a0 + d * k / div;
                    double t1 = a0 + d * (k + 1) / div;
                    // Pin the first/last vertices to the scanner endpoints so the arc meets
                    // adjacent flats exactly — cos/sin drift was leaving a seam and overlap.
                    double x0 = k == 0 ? seg[0] : arc[0] + Math.cos(t0);
                    double z0 = k == 0 ? seg[1] : arc[1] + Math.sin(t0);
                    double x1 = k == div - 1 ? seg[2] : arc[0] + Math.cos(t1);
                    double z1 = k == div - 1 ? seg[3] : arc[1] + Math.sin(t1);
                    subs.add(new double[]{x0, z0, x1, z1,
                            k / (double) div, (k + 1) / (double) div});
                }
            }
            for (double[] sub : subs) {
                double dxs = sub[2] - sub[0];
                double dzs = sub[3] - sub[1];
                double len = Math.max(1.0E-6, Math.sqrt(dxs * dxs + dzs * dzs));
                double nx = -dzs / len;
                double nz = dxs / len;
                if (arc != null) {
                    // Arc normals are radial: outward for convex, inward for concave — flip the
                    // chord normal when it disagrees with the radial direction the sign asks for.
                    double mx = (sub[0] + sub[2]) * 0.5 - arc[0];
                    double mz = (sub[1] + sub[3]) * 0.5 - arc[1];
                    if ((nx * mx + nz * mz) * arc[2] < 0.0) {
                        nx = -nx;
                        nz = -nz;
                    }
                }
                double lx = sub[0] - anchorPos.getX() + nx * 0.001;
                double lz = sub[1] - anchorPos.getZ() + nz * 0.001;
                double rx = sub[2] - anchorPos.getX() + nx * 0.001;
                double rz = sub[3] - anchorPos.getZ() + nz * 0.001;
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
