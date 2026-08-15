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
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
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

        int texId;
        ResourceLocation bloomTexture = null;
        if (mode == 0) {
            NdiStream stream = NdiManager.acquire(be.getSourceName());
            if (stream != null) {
                stream.uploadIfNeeded();
                texId = stream.getTextureId();
                bloomTexture = stream.getTextureLocation();
            } else {
                texId = 0;
            }
            if (texId == 0) {
                texId = FallbackTextures.black();
            }
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
                .add(f.scale(facing.surfaceOffset(THICKNESS, SURFACE_EPSILON)));

        Vec3 span = r.scale(pitch * w);
        Vec3 p00 = base;                          // bottom, viewer-left
        Vec3 p10 = base.add(span);                // bottom, viewer-right
        Vec3 p11 = base.add(span).add(0, h, 0);   // top, viewer-right
        Vec3 p01 = base.add(0, h, 0);             // top, viewer-left

        shader.safeGetUniform("LedParams").set(gridW, gridH, PIXEL_GAP, be.getEffectiveBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode, (float) pxPerBlock, CALIBRATION_VARIANCE);
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
        vertex(builder, mat, p00, 0.0F, 1.0F);
        vertex(builder, mat, p10, 1.0F, 1.0F);
        vertex(builder, mat, p11, 1.0F, 0.0F);
        vertex(builder, mat, p01, 0.0F, 0.0F);
        BufferUploader.drawWithShader(builder.end());

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
                ShimmerCompat.submitBloom(mat, p00, p10, p11, p01, shimmerTex, new float[]{
                        gridW, gridH, PIXEL_GAP, be.getEffectiveBrightness(),
                        be.getGamma(), (float) mode, (float) pxPerBlock, CALIBRATION_VARIANCE,
                        crop.u0(), crop.v0(), crop.du(), crop.dv()});
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
        Vec3 base = new Vec3(0.5, 0.0, 0.5)
                .subtract(r.scale(facing.pitch() * 0.5))
                .add(f.scale(facing.surfaceOffset(THICKNESS, SURFACE_EPSILON)));
        Vec3 span = r.scale(facing.pitch() * w);
        Vec3 p00 = base;
        Vec3 p10 = base.add(span);
        Vec3 p11 = base.add(span).add(0, h, 0);
        Vec3 p01 = base.add(0, h, 0);

        // Preferred: the baker pre-renders the full LED simulation into a texture outside
        // the pack's pipeline, so the wall keeps its real pixels under shaders. Brightness,
        // gamma and patterns are all baked in; the quad just displays it.
        ResourceLocation baked = LedWallBaker.request(be);
        if (baked != null) {
            Matrix4f bakedMat = poseStack.last().pose();
            VertexConsumer bakedVc = buffers.getBuffer(RenderType.entityTranslucentEmissive(baked));
            // The bake writes bottom-up (v=1 at the wall's bottom edge), so sample flipped.
            compatVertex(bakedVc, bakedMat, p00, 0.0F, 0.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
            compatVertex(bakedVc, bakedMat, p10, 1.0F, 0.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
            compatVertex(bakedVc, bakedMat, p11, 1.0F, 1.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
            compatVertex(bakedVc, bakedMat, p01, 0.0F, 1.0F, f, 1.0F, 1.0F, 1.0F, 1.0F);
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
        if (mode == 1) {
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
            compatVertex(vc, mat, p00, crop.u0(), crop.v1(), f, cr * bright, cg * bright, cb * bright, alpha);
            compatVertex(vc, mat, p10, crop.u1(), crop.v1(), f, cr * bright, cg * bright, cb * bright, alpha);
            compatVertex(vc, mat, p11, crop.u1(), crop.v0(), f, cr * bright, cg * bright, cb * bright, alpha);
            compatVertex(vc, mat, p01, crop.u0(), crop.v0(), f, cr * bright, cg * bright, cb * bright, alpha);
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
