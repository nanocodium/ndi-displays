package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
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
 * Draws everything a kinetic winch flies: the two suspension cables and the LED video
 * tile at its current (interpolated) drop. The tile is pure render geometry — no
 * blocks move — so the motion is perfectly smooth.
 *
 * The LED face goes through the same core shaders as the LED walls, with UvRegion set
 * to this tile's rectangle of the shared video canvas: as the tiles fly apart, each
 * keeps its slice of the image, which is what makes a bank of winches read as one
 * giant screen physically decomposing.
 */
public class KineticPanelRenderer implements BlockEntityRenderer<KineticWinchBlockEntity> {

    /** Tile cabinet thickness, blocks (a slim flown cabinet). */
    private static final float THICKNESS = 0.08F;
    private static final float SURFACE_EPSILON = 0.004F;
    /** Half-width of a rendered cable, blocks (thin steel wire rope). */
    private static final float CABLE_HALF = 0.01F;
    /** Cable inset from the tile's edge — twin suspension like a real flown tile. */
    private static final float CABLE_INSET = 0.2F;
    /** Where cables leave the winch housing (drum height within the block). */
    private static final float DRUM_Y = 0.275F;

    // Suspension hardware, Xine-style: a rigging bumper bar above the tile with link
    // plates down to the cabinet, and the wire ropes attached to the bar.
    private static final float BAR_GAP = 0.06F;
    private static final float BAR_HEIGHT = 0.07F;
    private static final float BAR_HALF_DEPTH = 0.045F;
    private static final float LINK_HALF = 0.03F;

    private static final float PIXEL_GAP = 0.15F;
    private static final float CALIBRATION_VARIANCE = 0.06F;

    // The flown tile is dressed as the LED Wall Panel cabinets, so a flying tile looks
    // exactly like a piece of wall that took off: same back plate, same side extrusion.
    private static final ResourceLocation TEX_FRONT =
            new ResourceLocation(NdiDisplays.MODID, "textures/block/led_panel_front.png");
    private static final ResourceLocation TEX_BACK =
            new ResourceLocation(NdiDisplays.MODID, "textures/block/led_panel_back.png");
    private static final ResourceLocation TEX_SIDE =
            new ResourceLocation(NdiDisplays.MODID, "textures/block/led_panel_side.png");

    @Override
    public void render(KineticWinchBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }

        float drop = Math.max(0.05F, be.getRenderDrop(partialTick));
        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
        Vec3 center = new Vec3(0.5, 0, 0.5);

        boolean flat = be.getOrientation() == KineticWinchBlockEntity.ORIENTATION_FLAT;
        float w = be.getPanelWidth();
        float h = be.getPanelHeight();

        // Panel top edge (cable attachment height).
        double topY = -drop;

        // The four corners of the LED surface plus its outward normal.
        Vec3 p00;
        Vec3 p10;
        Vec3 p11;
        Vec3 p01;
        Vec3 cableA;
        Vec3 cableB;

        if (flat) {
            // Horizontal tile facing straight down — the floating-sky element.
            double faceY = topY - THICKNESS - SURFACE_EPSILON;
            Vec3 c = center.add(0, faceY, 0);
            Vec3 ru = right.scale(w * 0.5);
            Vec3 fu = fwd.scale(h * 0.5);
            // Video top (v=0) sits on the far edge along the facing direction. The tile is
            // watched from BELOW, so the u axis runs mirrored relative to a top-down view —
            // mapping it as seen from above shows the image mirror-reversed to the audience.
            p00 = c.add(ru).subtract(fu);        // uv (0,1)
            p10 = c.subtract(ru).subtract(fu);   // uv (1,1)
            p11 = c.subtract(ru).add(fu);        // uv (1,0)
            p01 = c.add(ru).add(fu);             // uv (0,0)
            double inset = Math.min(CABLE_INSET, w * 0.25);
            cableA = center.add(right.scale(w * 0.5 - inset)).add(0, topY, 0);
            cableB = center.subtract(right.scale(w * 0.5 - inset)).add(0, topY, 0);
        } else {
            // Vertical banner tile facing the block's FACING direction.
            Vec3 face = center.add(fwd.scale(THICKNESS * 0.5 + SURFACE_EPSILON));
            Vec3 ru = right.scale(w * 0.5);
            // u runs viewer-left → viewer-right for someone standing on the facing side.
            p00 = face.add(ru).add(0, topY - h, 0);        // uv (0,1) bottom-left (viewer)
            p10 = face.subtract(ru).add(0, topY - h, 0);   // uv (1,1)
            p11 = face.subtract(ru).add(0, topY, 0);       // uv (1,0) top-right
            p01 = face.add(ru).add(0, topY, 0);            // uv (0,0)
            double inset = Math.min(CABLE_INSET, w * 0.25);
            cableA = center.add(right.scale(w * 0.5 - inset)).add(0, topY, 0);
            cableB = center.subtract(right.scale(w * 0.5 - inset)).add(0, topY, 0);
        }

        Matrix4f mat = poseStack.last().pose();

        // --- Suspension hardware + cabinet through the normal buffered pipeline.
        VertexConsumer solid = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));

        double barBottomY = topY + BAR_GAP;
        double barTopY = barBottomY + BAR_HEIGHT;

        // Rigging bumper bar spanning the tile, hanging from the two wire ropes.
        Vec3 barC1 = center.add(right.scale(w * 0.5 - 0.04)).add(fwd.scale(BAR_HALF_DEPTH));
        Vec3 barC2 = center.subtract(right.scale(w * 0.5 - 0.04)).subtract(fwd.scale(BAR_HALF_DEPTH));
        shadedBox(solid, mat,
                new Vec3(Math.min(barC1.x, barC2.x), barBottomY, Math.min(barC1.z, barC2.z)),
                new Vec3(Math.max(barC1.x, barC2.x), barTopY, Math.max(barC1.z, barC2.z)),
                0.22F, packedLight);

        // Link plates connecting the bumper bar to the cabinet at each suspension point.
        for (Vec3 attach : new Vec3[]{cableA, cableB}) {
            Vec3 l1 = attach.add(right.scale(LINK_HALF)).add(fwd.scale(0.015));
            Vec3 l2 = attach.subtract(right.scale(LINK_HALF)).subtract(fwd.scale(0.015));
            shadedBox(solid, mat,
                    new Vec3(Math.min(l1.x, l2.x), topY - 0.01, Math.min(l1.z, l2.z)),
                    new Vec3(Math.max(l1.x, l2.x), barBottomY + 0.01, Math.max(l1.z, l2.z)),
                    0.12F, packedLight);
        }

        // Wire ropes: from the top of the bar up to the winch drum.
        drawCable(solid, mat, new Vec3(cableA.x, barTopY - 0.01, cableA.z),
                new Vec3(cableA.x, DRUM_Y, cableA.z), right, fwd, packedLight);
        drawCable(solid, mat, new Vec3(cableB.x, barTopY - 0.01, cableB.z),
                new Vec3(cableB.x, DRUM_Y, cableB.z), right, fwd, packedLight);
        if (!be.isMesh()) {
            Vec3 boxMin;
            Vec3 boxMax;
            Direction ledFace;
            if (flat) {
                Vec3 ru = right.scale(w * 0.5);
                Vec3 fu = fwd.scale(h * 0.5);
                Vec3 c1 = center.subtract(ru).subtract(fu).add(0, topY - THICKNESS, 0);
                Vec3 c2 = center.add(ru).add(fu).add(0, topY, 0);
                boxMin = new Vec3(Math.min(c1.x, c2.x), Math.min(c1.y, c2.y), Math.min(c1.z, c2.z));
                boxMax = new Vec3(Math.max(c1.x, c2.x), Math.max(c1.y, c2.y), Math.max(c1.z, c2.z));
                ledFace = Direction.DOWN;
            } else {
                Vec3 ru = right.scale(w * 0.5);
                Vec3 fu = fwd.scale(THICKNESS * 0.5);
                Vec3 c1 = center.subtract(ru).subtract(fu).add(0, topY - h, 0);
                Vec3 c2 = center.add(ru).add(fu).add(0, topY, 0);
                boxMin = new Vec3(Math.min(c1.x, c2.x), Math.min(c1.y, c2.y), Math.min(c1.z, c2.z));
                boxMax = new Vec3(Math.max(c1.x, c2.x), Math.max(c1.y, c2.y), Math.max(c1.z, c2.z));
                ledFace = facing;
            }
            drawCabinet(buffers, mat, boxMin, boxMax, ledFace, packedLight);
        }

        // --- LED surface.
        float uOff = (float) be.getCanvasCol() / be.getCanvasCols();
        float vOff = (float) be.getCanvasRow() / be.getCanvasRows();
        float uScale = 1.0F / be.getCanvasCols();
        float vScale = 1.0F / be.getCanvasRows();
        int mode = be.getTestPattern();
        float gridW = be.getPixelsPerBlock() * w;
        float gridH = be.getPixelsPerBlock() * h;

        if (ShaderPackCompat.shaderPackActive()) {
            renderShaderPackCompat(be, mode, p00, p10, p11, p01, flat ? new Vec3(0, -1, 0) : fwd,
                    uOff, vOff, uScale, vScale, poseStack, buffers);
            return;
        }

        ShaderInstance shader = be.isMesh() ? ClientSetup.ledWallTransparentShader : ClientSetup.ledWallShader;
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
        shader.safeGetUniform("UvRegion").set(uOff, vOff, uScale, vScale);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        if (be.isMesh()) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        } else {
            RenderSystem.disableBlend();
        }
        RenderSystem.disableCull();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertex(builder, mat, p00, 0.0F, 1.0F);
        vertex(builder, mat, p10, 1.0F, 1.0F);
        vertex(builder, mat, p11, 1.0F, 0.0F);
        vertex(builder, mat, p01, 0.0F, 0.0F);
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.enableCull();
        if (be.isMesh()) {
            RenderSystem.disableBlend();
        }
    }

    /**
     * Shader-pack fallback: flat emissive video quad through a vanilla RenderType the
     * pack can patch. The canvas slice is applied directly to the vertex UVs — no LED
     * structure, but the image (and the decomposing-screen effect) survives any pack.
     */
    private void renderShaderPackCompat(KineticWinchBlockEntity be, int mode,
                                        Vec3 p00, Vec3 p10, Vec3 p11, Vec3 p01, Vec3 normal,
                                        float uOff, float vOff, float uScale, float vScale,
                                        PoseStack poseStack, MultiBufferSource buffers) {
        float bright = be.getEffectiveBrightness();
        float alpha = be.isMesh() ? 0.55F : 1.0F;
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

        float u0 = uOff;
        float u1 = uOff + uScale;
        float v0 = vOff;
        float v1 = vOff + vScale;
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
        emissiveVertex(vc, mat, p00, u0, v1, normal, cr * bright, cg * bright, cb * bright, alpha);
        emissiveVertex(vc, mat, p10, u1, v1, normal, cr * bright, cg * bright, cb * bright, alpha);
        emissiveVertex(vc, mat, p11, u1, v0, normal, cr * bright, cg * bright, cb * bright, alpha);
        emissiveVertex(vc, mat, p01, u0, v0, normal, cr * bright, cg * bright, cb * bright, alpha);
    }

    /** An axis-aligned box in a flat dark shade — rigging hardware (bumper bar, links). */
    private static void shadedBox(VertexConsumer vc, Matrix4f mat, Vec3 min, Vec3 max,
                                  float shade, int light) {
        // top / bottom
        shadedQuad(vc, mat, shade, light,
                new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z),
                new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z));
        shadedQuad(vc, mat, shade * 0.7F, light,
                new Vec3(min.x, min.y, max.z), new Vec3(max.x, min.y, max.z),
                new Vec3(max.x, min.y, min.z), new Vec3(min.x, min.y, min.z));
        // north / south
        shadedQuad(vc, mat, shade * 0.85F, light,
                new Vec3(min.x, max.y, min.z), new Vec3(min.x, min.y, min.z),
                new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z));
        shadedQuad(vc, mat, shade * 0.85F, light,
                new Vec3(max.x, max.y, max.z), new Vec3(max.x, min.y, max.z),
                new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z));
        // west / east
        shadedQuad(vc, mat, shade * 0.9F, light,
                new Vec3(min.x, max.y, max.z), new Vec3(min.x, min.y, max.z),
                new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z));
        shadedQuad(vc, mat, shade * 0.9F, light,
                new Vec3(max.x, max.y, min.z), new Vec3(max.x, min.y, min.z),
                new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z));
    }

    private static void shadedQuad(VertexConsumer vc, Matrix4f mat, float shade, int light,
                                   Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        litVertex(vc, mat, a, shade, light);
        litVertex(vc, mat, b, shade, light);
        litVertex(vc, mat, c, shade, light);
        litVertex(vc, mat, d, shade, light);
    }

    /** A cable as two crossed thin quads, so it reads from every angle. */
    private static void drawCable(VertexConsumer vc, Matrix4f mat, Vec3 bottom, Vec3 top,
                                  Vec3 right, Vec3 fwd, int light) {
        cableQuad(vc, mat, bottom, top, right.scale(CABLE_HALF), light);
        cableQuad(vc, mat, bottom, top, fwd.scale(CABLE_HALF), light);
    }

    private static void cableQuad(VertexConsumer vc, Matrix4f mat, Vec3 a, Vec3 b, Vec3 half, int light) {
        float shade = 0.16F;
        litVertex(vc, mat, a.subtract(half), shade, light);
        litVertex(vc, mat, a.add(half), shade, light);
        litVertex(vc, mat, b.add(half), shade, light);
        litVertex(vc, mat, b.subtract(half), shade, light);
        // Back face, so the quad is visible from both sides even with cull enabled.
        litVertex(vc, mat, b.subtract(half), shade, light);
        litVertex(vc, mat, b.add(half), shade, light);
        litVertex(vc, mat, a.add(half), shade, light);
        litVertex(vc, mat, a.subtract(half), shade, light);
    }

    /**
     * The tile's cabinet, dressed as LED Wall Panel blocks: back plate on the face
     * opposite the LEDs, side extrusion on the edges, and the panel-front texture as
     * backing behind the emissive LED quad. Textures tile once per block, so a 2×2
     * tile reads as four wall cabinets bolted together.
     */
    private static void drawCabinet(MultiBufferSource buffers, Matrix4f mat,
                                    Vec3 min, Vec3 max, Direction ledFace, int light) {
        for (Direction dir : Direction.values()) {
            ResourceLocation tex = dir == ledFace ? TEX_FRONT
                    : dir == ledFace.getOpposite() ? TEX_BACK : TEX_SIDE;
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(tex));
            faceQuad(vc, mat, min, max, dir, light);
        }
    }

    /** One axis-aligned face of the cabinet box, texture tiled per block. */
    private static void faceQuad(VertexConsumer vc, Matrix4f mat, Vec3 min, Vec3 max,
                                 Direction dir, int light) {
        float sx = (float) (max.x - min.x);
        float sy = (float) (max.y - min.y);
        float sz = (float) (max.z - min.z);
        Vec3 n = Vec3.atLowerCornerOf(dir.getNormal());
        switch (dir) {
            case DOWN -> texQuad(vc, mat, n, light, sx, sz,
                    new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z),
                    new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z));
            case UP -> texQuad(vc, mat, n, light, sx, sz,
                    new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z),
                    new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z));
            case NORTH -> texQuad(vc, mat, n, light, sx, sy,
                    new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z),
                    new Vec3(max.x, min.y, min.z), new Vec3(min.x, min.y, min.z));
            case SOUTH -> texQuad(vc, mat, n, light, sx, sy,
                    new Vec3(min.x, max.y, max.z), new Vec3(max.x, max.y, max.z),
                    new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z));
            case WEST -> texQuad(vc, mat, n, light, sz, sy,
                    new Vec3(min.x, max.y, min.z), new Vec3(min.x, max.y, max.z),
                    new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z));
            case EAST -> texQuad(vc, mat, n, light, sz, sy,
                    new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z),
                    new Vec3(max.x, min.y, max.z), new Vec3(max.x, min.y, min.z));
        }
    }

    /** Quad with UVs (0,0)→(uTiles,vTiles) over corners a→b→c→d. */
    private static void texQuad(VertexConsumer vc, Matrix4f mat, Vec3 normal, int light,
                                float uTiles, float vTiles, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        texVertex(vc, mat, a, 0, 0, normal, light);
        texVertex(vc, mat, b, uTiles, 0, normal, light);
        texVertex(vc, mat, c, uTiles, vTiles, normal, light);
        texVertex(vc, mat, d, 0, vTiles, normal, light);
    }

    private static void texVertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float u, float v,
                                  Vec3 normal, int light) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal((float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
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
    public boolean shouldRenderOffScreen(KineticWinchBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
