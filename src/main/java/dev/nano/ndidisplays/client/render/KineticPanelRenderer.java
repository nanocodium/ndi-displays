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

        // Non-tile payloads hang from a single hook cable and never tilt.
        if (be.getPayload() != KineticWinchBlockEntity.PAYLOAD_LED_TILE) {
            renderSuspendedPayload(be, partialTick, poseStack, buffers, packedLight);
            return;
        }

        // Twin mode: each cable is its own motor. The tile hangs level at the mean of
        // the two drops and rolls around the facing axis to meet both attachment
        // heights — same UV window on the shared canvas, only the transform changes.
        float dropA = Math.max(0.05F, be.getRenderDrop(partialTick));
        float dropB = Math.max(0.05F, be.getRenderDropB(partialTick));
        float drop = (dropA + dropB) * 0.5F;
        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
        Vec3 center = new Vec3(0.5, 0, 0.5);

        // Rotation by +tilt around fwd moves the +right side down by sin(tilt), so the
        // A cable (at +right) sitting deeper than B needs a positive angle.
        float tiltSin = net.minecraft.util.Mth.clamp((dropA - dropB) / be.cableSpan(), -0.95F, 0.95F);
        float tilt = (float) Math.asin(tiltSin);
        boolean tilted = Math.abs(tilt) > 1e-4F;

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

        // The cables hang vertically in world space, so they draw through the untilted
        // matrix; everything bolted to the tile (bar, links, cabinet, LED face) draws
        // through a pose rolled around the facing axis at the tile's top-centre pivot.
        Matrix4f matWorld = poseStack.last().pose();
        poseStack.pushPose();
        if (tilted) {
            poseStack.translate(0.5, topY, 0.5);
            poseStack.mulPose(new org.joml.Quaternionf()
                    .setAngleAxis(tilt, (float) fwd.x, (float) fwd.y, (float) fwd.z));
            poseStack.translate(-0.5, -topY, -0.5);
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

        // Wire ropes: from the top of the (possibly tilted) bar straight up to the
        // drum. The bar-end positions are rotated into world space by hand, then each
        // rope rises vertically from wherever its end landed — winch A pays out more
        // cable than winch B, which is exactly what the twin mode simulates.
        Vec3 pivot = new Vec3(0.5, topY, 0.5);
        Vec3 ropeA = rotateAroundPivot(new Vec3(cableA.x, barTopY - 0.01, cableA.z), pivot, fwd, tilt);
        Vec3 ropeB = rotateAroundPivot(new Vec3(cableB.x, barTopY - 0.01, cableB.z), pivot, fwd, tilt);
        drawCable(solid, matWorld, ropeA, new Vec3(ropeA.x, DRUM_Y, ropeA.z), right, fwd, packedLight);
        drawCable(solid, matWorld, ropeB, new Vec3(ropeB.x, DRUM_Y, ropeB.z), right, fwd, packedLight);
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
            poseStack.popPose();
            return;
        }

        ShaderInstance shader = be.isMesh() ? ClientSetup.ledWallTransparentShader : ClientSetup.ledWallShader;
        if (shader == null) {
            poseStack.popPose();
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
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ payloads

    /** Ball radius for the mirror ball and the kinetic sphere, blocks. */
    private static final float BALL_RADIUS = 0.38F;
    private static final int SPHERE_LAT = 12;
    private static final int SPHERE_LON = 18;

    /**
     * A non-tile payload: one hook cable straight down from the drum, then the
     * kinetic sphere, mirror ball or flown Theatrical fixture at the hook.
     */
    private void renderSuspendedPayload(KineticWinchBlockEntity be, float partialTick,
                                        PoseStack poseStack, MultiBufferSource buffers,
                                        int packedLight) {
        float drop = Math.max(0.05F, be.getRenderDrop(partialTick));
        double hookY = -drop;
        Direction facing = be.getFacing();
        Vec3 fwd = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
        Matrix4f mat = poseStack.last().pose();

        VertexConsumer solid = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        drawCable(solid, mat, new Vec3(0.5, hookY, 0.5), new Vec3(0.5, DRUM_Y, 0.5),
                right, fwd, packedLight);
        // Hook / motor canister at the top of the payload.
        shadedBox(solid, mat, new Vec3(0.5 - 0.05, hookY - 0.08, 0.5 - 0.05),
                new Vec3(0.5 + 0.05, hookY, 0.5 + 0.05), 0.18F, packedLight);

        Vec3 ballCenter = new Vec3(0.5, hookY - 0.08 - BALL_RADIUS, 0.5);
        switch (be.getPayload()) {
            case KineticWinchBlockEntity.PAYLOAD_MIRROR_BALL ->
                    renderMirrorBall(be, partialTick, ballCenter, mat, buffers, packedLight);
            case KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE ->
                    renderKineticSphere(be, ballCenter, mat, buffers);
            case KineticWinchBlockEntity.PAYLOAD_FIXTURE ->
                    FlownFixtureRenderer.render(be, partialTick, hookY, poseStack, buffers, packedLight);
            default -> {
            }
        }
    }

    /**
     * The classic disco mirror ball: a faceted sphere in slowly rotating specular
     * greys, a few facets catching the light as full-bright glints.
     */
    private static void renderMirrorBall(KineticWinchBlockEntity be, float partialTick,
                                         Vec3 center, Matrix4f mat, MultiBufferSource buffers,
                                         int packedLight) {
        float spin = ((be.getLevel().getGameTime() % 720) + partialTick) * 0.008F * (float) Math.PI;
        VertexConsumer facets = buffers.getBuffer(
                RenderType.entityCutoutNoCull(FallbackTextures.whiteLocation()));
        VertexConsumer glints = buffers.getBuffer(
                RenderType.entityTranslucentEmissive(FallbackTextures.whiteLocation()));
        for (int i = 0; i < SPHERE_LAT; i++) {
            double t0 = Math.PI * i / SPHERE_LAT;
            double t1 = Math.PI * (i + 1) / SPHERE_LAT;
            for (int j = 0; j < SPHERE_LON; j++) {
                double p0 = 2 * Math.PI * j / SPHERE_LON + spin;
                double p1 = 2 * Math.PI * (j + 1) / SPHERE_LON + spin;
                Vec3 a = spherePoint(center, BALL_RADIUS, t0, p0);
                Vec3 b = spherePoint(center, BALL_RADIUS, t0, p1);
                Vec3 c = spherePoint(center, BALL_RADIUS, t1, p1);
                Vec3 d = spherePoint(center, BALL_RADIUS, t1, p0);
                float h = hash(i, j);
                if (h > 0.90F) {
                    // A facet flashing the room: full-bright, like catching a beam.
                    Vec3 n = a.subtract(center).normalize();
                    emissiveVertex(glints, mat, a, 0, 0, n, 1, 1, 1, 1);
                    emissiveVertex(glints, mat, b, 1, 0, n, 1, 1, 1, 1);
                    emissiveVertex(glints, mat, c, 1, 1, n, 1, 1, 1, 1);
                    emissiveVertex(glints, mat, d, 0, 1, n, 1, 1, 1, 1);
                } else {
                    float shade = 0.30F + 0.45F * h;
                    shadedQuad(facets, mat, shade, packedLight, a, b, c, d);
                }
            }
        }
    }

    /**
     * The kinetic-lights RGB sphere: an emissive globe in the winch's DMX colour with
     * a soft translucent halo — hundreds of these on a grid make 3D colour waves.
     */
    private static void renderKineticSphere(KineticWinchBlockEntity be, Vec3 center,
                                            Matrix4f mat, MultiBufferSource buffers) {
        float[] rgb = be.getSphereColor();
        VertexConsumer vc = buffers.getBuffer(
                RenderType.entityTranslucentEmissive(FallbackTextures.whiteLocation()));
        drawEmissiveSphere(vc, mat, center, BALL_RADIUS * 0.9F, rgb[0], rgb[1], rgb[2], 1.0F);
        // Halo: a slightly larger translucent shell that reads as glow at distance.
        drawEmissiveSphere(vc, mat, center, BALL_RADIUS * 1.12F,
                rgb[0], rgb[1], rgb[2], 0.18F);
    }

    private static void drawEmissiveSphere(VertexConsumer vc, Matrix4f mat, Vec3 center,
                                           float radius, float r, float g, float b, float alpha) {
        for (int i = 0; i < SPHERE_LAT; i++) {
            double t0 = Math.PI * i / SPHERE_LAT;
            double t1 = Math.PI * (i + 1) / SPHERE_LAT;
            for (int j = 0; j < SPHERE_LON; j++) {
                double p0 = 2 * Math.PI * j / SPHERE_LON;
                double p1 = 2 * Math.PI * (j + 1) / SPHERE_LON;
                Vec3 a = spherePoint(center, radius, t0, p0);
                Vec3 bb = spherePoint(center, radius, t0, p1);
                Vec3 c = spherePoint(center, radius, t1, p1);
                Vec3 d = spherePoint(center, radius, t1, p0);
                Vec3 n = a.subtract(center).normalize();
                emissiveVertex(vc, mat, a, 0, 0, n, r, g, b, alpha);
                emissiveVertex(vc, mat, bb, 1, 0, n, r, g, b, alpha);
                emissiveVertex(vc, mat, c, 1, 1, n, r, g, b, alpha);
                emissiveVertex(vc, mat, d, 0, 1, n, r, g, b, alpha);
            }
        }
    }

    private static Vec3 spherePoint(Vec3 center, float radius, double theta, double phi) {
        return center.add(radius * Math.sin(theta) * Math.cos(phi),
                radius * Math.cos(theta),
                radius * Math.sin(theta) * Math.sin(phi));
    }

    /** Deterministic per-facet pseudo-random in [0,1) — mirror-ball facet variation. */
    private static float hash(int i, int j) {
        int h = i * 73856093 ^ j * 19349663;
        return ((h >> 8) & 0xFF) / 256.0F;
    }

    /** Rodrigues rotation of {@code p} by {@code angle} around {@code axis} through {@code pivot}. */
    private static Vec3 rotateAroundPivot(Vec3 p, Vec3 pivot, Vec3 axis, float angle) {
        if (Math.abs(angle) < 1e-4F) {
            return p;
        }
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Vec3 d = p.subtract(pivot);
        return pivot.add(d.scale(cos))
                .add(axis.cross(d).scale(sin))
                .add(axis.scale(axis.dot(d) * (1.0 - cos)));
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

    /** Dark box for the flown-fixture placeholder when Theatrical models are unavailable. */
    static void placeholderBox(VertexConsumer vc, Matrix4f mat, Vec3 min, Vec3 max, int light) {
        shadedBox(vc, mat, min, max, 0.15F, light);
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
