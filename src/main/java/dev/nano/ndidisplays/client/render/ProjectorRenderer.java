package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.ProjectorBlockEntity;
import dev.nano.ndidisplays.client.ClientSetup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a projector's content onto the world.
 *
 * The pipeline is the real thing: every candidate surface point goes
 * {@code world → projector view matrix → projector projection matrix → ÷w → UV}, and the shader
 * samples the video at that UV. What makes it performant in Minecraft is that the "surface" is
 * not the whole world but a cached drape mesh: the visible block faces inside the frustum,
 * found by scanning the frustum volume and raycasting each face back to the lens for occlusion —
 * a shadow map computed at face granularity on the CPU, refreshed periodically instead of per
 * frame. Faces the projector cannot see get no quad, so a pillar in front of the lens cuts a
 * pillar-shaped hole in the image on the wall behind it, like a hand in front of a real beam.
 *
 * Projector UVs are computed per vertex (the perspective divide included), which is exact at
 * vertices and affine between them; block faces subtend small angles so the interior error is
 * invisible, and faces that span too much of the frame are subdivided until it is.
 */
public class ProjectorRenderer implements BlockEntityRenderer<ProjectorBlockEntity> {

    /** Drape mesh refresh period, ms. Config changes rebuild immediately via the revision. */
    private static final long REBUILD_MS = 1500L;

    /**
     * Hard budgets. These were sized for the CPU-raycast era, when every face cost a world clip;
     * occlusion is per-pixel on the GPU now, so a face costs one quad and the caps can carry a
     * whole mountainside. A frustum needing more than this drops its farthest faces first.
     */
    private static final int MAX_FACES = 30000;
    private static final int MAX_QUADS = 30000;

    /** A face whose projected span exceeds this fraction of the frame gets subdivided. */
    private static final float SUBDIV_UV_SPAN = 0.08F;
    private static final int SUBDIV_MAX_DEPTH = 4;

    /** Push the drape off the surface; polygon offset does the rest at range. */
    private static final double SURFACE_EPSILON = 0.0035;

    /** One drape quad: 4 × (x, y, z, u, v, intensity), block-local to the projector pos. */
    private record Mesh(List<float[]> quads, Matrix4f matrix) {
    }

    @Override
    public void render(ProjectorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        if (ProjectorShadows.inDepthPass) {
            return; // the beam must not paint or line its own shadow map
        }
        ProjectorShadows.Shadow shadow = ProjectorShadows.register(be);
        Mesh mesh = meshFor(be, level);
        if (be.showFrustum()) {
            drawFrustum(be, poseStack, buffers);
        }
        if (mesh == null || mesh.quads().isEmpty()) {
            return;
        }
        ShaderInstance shader = ClientSetup.projectorShader;
        if (shader == null || ShaderPackCompat.shaderPackActive()) {
            // Shader packs replace the world pipeline; our core shader cannot join it. The
            // frustum preview above still shows where the projector points.
            return;
        }

        int mode = be.getTestPattern();
        int texId;
        if (mode == 0) {
            texId = ScreenVideo.textureId(be.getSourceName());
            if (texId == FallbackTextures.black()) {
                return; // no signal: a projector with no input throws no light
            }
        } else {
            texId = FallbackTextures.white();
        }

        // The light itself: a faint additive cone out of the lens — the haze a projector cuts
        // through a room. Short and tighter than the frustum, brightest at the lens.
        drawBeam(be, poseStack);

        shader.safeGetUniform("ProjParams").set(be.getBrightness(), be.getFeather(),
                (float) mode, be.isAdditive() ? 1.0F : 0.0F);
        BlockPos anchor = be.getBlockPos();
        // The whole optical model, anchored to this block entity's local space: the fragment
        // shader projects every pixel itself, so UVs are exact everywhere, not just at vertices.
        Matrix4f imageMat = new Matrix4f(mesh.matrix())
                .translate(anchor.getX(), anchor.getY(), anchor.getZ());
        shader.safeGetUniform("ProjectorMat").set(imageMat);
        // Vertices are baked through the BER pose (camera-relative view space); the shader
        // inverts that to recover block-local positions before projecting them.
        shader.safeGetUniform("InvPoseMat").set(
                new Matrix4f(poseStack.last().pose()).invert());
        boolean shadowed = shadow.ready;
        if (shadowed) {
            Matrix4f shadowMat = new Matrix4f(shadow.matrix)
                    .translate(anchor.getX(), anchor.getY(), anchor.getZ());
            shader.safeGetUniform("ShadowMat").set(shadowMat);
            RenderSystem.setShaderTexture(1, shadow.target.getDepthTextureId());
        }
        shader.safeGetUniform("ShadowParams").set(ProjectorShadows.BIAS,
                1.0F / ProjectorShadows.MAP_SIZE, shadowed ? 1.0F : 0.0F, shadow.farPlane);
        Vec3 lens = lensOrigin(be);
        shader.safeGetUniform("LensPos").set(
                (float) (lens.x - anchor.getX()), (float) (lens.y - anchor.getY()),
                (float) (lens.z - anchor.getZ()), 0.0F);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        if (be.isAdditive()) {
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        // Winding is not normalised across the six face orientations; the geometry is already
        // one-sided by construction (only faces looking at the lens are collected).
        RenderSystem.disableCull();
        // The drape sits a hair off every surface; polygon offset keeps that hair from
        // z-fighting at distance, same treatment as the video faces on cabinets.
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        RenderSystem.enablePolygonOffset();

        Matrix4f mat = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (float[] q : mesh.quads()) {
            for (int i = 0; i < 4; i++) {
                int o = i * 6;
                float lum = Math.abs(q[o + 5]);
                // UV0 carries the face normal's x/y (full float precision); the normal's z sign
                // rides in the luminance slot's sign and z is recovered in the shader. Colour g
                // marks that sign for the shader (0 = negative z, 1 = positive).
                builder.vertex(mat, q[o], q[o + 1], q[o + 2])
                        .uv(q[o + 3], q[o + 4])
                        .color(lum, q[o + 5] < 0.0F ? 0.0F : 1.0F, 0.0F, 1.0F)
                        .endVertex();
            }
        }
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ optics

    /** Lens position, world space: the centre of the head. */
    private static Vec3 lensOrigin(ProjectorBlockEntity be) {
        return Vec3.atCenterOf(be.getBlockPos());
    }

    static Vec3 lensDirection(ProjectorBlockEntity be) {
        double yaw = Math.toRadians(be.getYaw());
        double pitch = Math.toRadians(be.getPitch());
        double c = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * c, Math.sin(pitch), Math.cos(yaw) * c);
    }

    /**
     * The projector's full clip transform: keystone ∘ projection(+lens shift) ∘ view. A world
     * point through this and a perspective divide is a frame UV — the whole optical model in
     * one matrix.
     */
    private static Matrix4f projectorMatrix(ProjectorBlockEntity be, Vec3 origin) {
        Vec3 dir = lensDirection(be);
        Vec3 up = Math.abs(dir.y) > 0.999 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);

        Matrix4f view = new Matrix4f().lookAt(
                (float) origin.x, (float) origin.y, (float) origin.z,
                (float) (origin.x + dir.x), (float) (origin.y + dir.y), (float) (origin.z + dir.z),
                (float) up.x, (float) up.y, (float) up.z);

        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(be.getFov()), be.getAspect(), be.getNear(), be.getFar());
        // Lens shift: slide the frustum sideways without tilting it, exactly like the physical
        // control — implemented as an NDC translation folded into the projection.
        proj.m20(proj.m20() + be.getShiftH() * 1.0F);
        proj.m21(proj.m21() + be.getShiftV() * 1.0F);

        Matrix4f full = new Matrix4f(proj).mul(view);
        // Keystone: tilt the image plane. Adding x/y into w is the projective shear a real
        // keystone control performs; positive V squeezes the top, like aiming up at a wall.
        if (be.getKeystoneH() != 0.0F || be.getKeystoneV() != 0.0F) {
            Matrix4f key = new Matrix4f();
            key.m03(be.getKeystoneH());
            key.m13(be.getKeystoneV());
            full = key.mul(full, new Matrix4f());
        }
        return full;
    }

    /** World point → frame UV + depth; null when behind the lens or outside near/far. */
    private static float[] project(Matrix4f m, Vec3 origin, double x, double y, double z) {
        Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1.0F);
        m.transform(v);
        if (v.w <= 1.0E-4F) {
            return null;
        }
        return new float[]{v.x / v.w * 0.5F + 0.5F, 0.5F - v.y / v.w * 0.5F, v.z / v.w};
    }

    // ------------------------------------------------------------------ drape mesh

    private static Mesh meshFor(ProjectorBlockEntity be, Level level) {
        long now = System.currentTimeMillis();
        if (be.clientMesh instanceof Mesh cached
                && be.clientMeshRevision == be.clientRevision
                && now - be.clientMeshBuiltAt < REBUILD_MS) {
            return cached;
        }
        Mesh mesh = buildMesh(be, level);
        be.clientMesh = mesh;
        be.clientMeshRevision = be.clientRevision;
        be.clientMeshBuiltAt = now;
        return mesh;
    }

    private static Mesh buildMesh(ProjectorBlockEntity be, Level level) {
        Vec3 origin = lensOrigin(be);
        Matrix4f m = projectorMatrix(be, origin);
        Vec3 dir = lensDirection(be);
        double far = be.getFar();
        double near = be.getNear();
        // Cone half-angle covering the frustum diagonal, with slack for shift/keystone.
        double halfDiag = Math.toRadians(be.getFov() * 0.5)
                * Math.sqrt(1.0 + be.getAspect() * be.getAspect());
        double cosCone = Math.cos(Math.min(Math.PI * 0.49, halfDiag * 1.35
                + Math.abs(be.getShiftH()) * 0.5 + Math.abs(be.getShiftV()) * 0.5));

        BlockPos lens = be.getBlockPos();
        int r = (int) Math.ceil(far);
        BlockPos min = lens.offset(-r, -r, -r);
        BlockPos max = lens.offset(r, r, r);

        // Candidate faces: exposed solid faces inside the cone, nearest first.
        record Candidate(double dist2, BlockPos pos, Direction face) {
        }
        List<Candidate> candidates = new ArrayList<>();
        double far2 = far * far;
        int minSection = level.getSectionIndex(min.getY());
        int maxSection = level.getSectionIndex(max.getY());
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                for (int si = Math.max(0, minSection);
                     si <= Math.min(chunk.getSections().length - 1, maxSection); si++) {
                    var section = chunk.getSection(si);
                    if (section.hasOnlyAir()) {
                        continue;
                    }
                    int baseY = level.getSectionYFromSectionIndex(si) << 4;
                    int x0 = Math.max(min.getX(), cx << 4);
                    int x1 = Math.min(max.getX(), (cx << 4) + 15);
                    int z0 = Math.max(min.getZ(), cz << 4);
                    int z1 = Math.min(max.getZ(), (cz << 4) + 15);
                    int y0 = Math.max(min.getY(), baseY);
                    int y1 = Math.min(max.getY(), baseY + 15);
                    BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (state.isAir()) {
                                    continue;
                                }
                                p.set(x, y, z);
                                if (!state.isSolidRender(level, p)) {
                                    continue;
                                }
                                double bx = x + 0.5 - origin.x;
                                double by = y + 0.5 - origin.y;
                                double bz = z + 0.5 - origin.z;
                                double d2 = bx * bx + by * by + bz * bz;
                                if (d2 > far2 || d2 < 1.0E-4) {
                                    continue;
                                }
                                double invD = 1.0 / Math.sqrt(d2);
                                if ((bx * dir.x + by * dir.y + bz * dir.z) * invD < cosCone) {
                                    continue;
                                }
                                for (Direction face : Direction.values()) {
                                    // Face must look at the lens and not be buried.
                                    Vec3 n = Vec3.atLowerCornerOf(face.getNormal());
                                    Vec3 c = new Vec3(x + 0.5 + n.x * 0.5, y + 0.5 + n.y * 0.5,
                                            z + 0.5 + n.z * 0.5);
                                    if (n.dot(origin.subtract(c)) <= 0.0) {
                                        continue;
                                    }
                                    BlockPos np = p.relative(face);
                                    BlockState nb = level.getBlockState(np);
                                    if (nb.isSolidRender(level, np)) {
                                        continue;
                                    }
                                    // Anything the beam can reach gets light — "near" shapes
                                    // the projection matrix, it is not a hole around the head.
                                    double fd = c.distanceToSqr(origin);
                                    if (fd > 0.2) {
                                        candidates.add(new Candidate(fd, p.immutable(), face));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.dist2, b.dist2));

        List<float[]> quads = new ArrayList<>();
        BlockPos anchor = be.getBlockPos();
        var player = Minecraft.getInstance().player;
        int rays = 0;
        for (Candidate cand : candidates) {
            if (rays >= MAX_FACES || quads.size() >= MAX_QUADS) {
                break;
            }
            rays++;
            Vec3 n = Vec3.atLowerCornerOf(cand.face.getNormal());
            // No CPU occlusion here any more: the shadow map resolves visibility per PIXEL on
            // the GPU, entities included. The mesh only has to offer every surface the beam
            // could reach; the depth compare decides what actually receives light.
            emitFace(quads, m, origin, anchor, cand.pos, cand.face, n, 0);
        }
        if (quads.isEmpty() && !candidates.isEmpty()) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "[ndidisplays] projector at {}: {} candidate faces, all rejected — check aim/occlusion",
                    be.getBlockPos(), candidates.size());
        }
        return new Mesh(quads, m);
    }

    /**
     * One face → one or more drape quads with per-vertex projector UVs, subdivided while its
     * projected span is large enough for affine interpolation to visibly disagree with the true
     * perspective.
     */
    private static void emitFace(List<float[]> quads, Matrix4f m, Vec3 origin, BlockPos anchor,
                                 BlockPos pos, Direction face, Vec3 n, int depth) {
        Vec3[] corners = faceCorners(pos, face, n);
        emitQuad(quads, m, origin, anchor, corners, n, depth);
    }

    private static void emitQuad(List<float[]> quads, Matrix4f m, Vec3 origin, BlockPos anchor,
                                 Vec3[] c, Vec3 n, int depth) {
        if (quads.size() >= MAX_QUADS) {
            return;
        }
        // Frame cull only: UVs and occlusion are both per-fragment now. A face is dropped only
        // when every corner projects cleanly outside the same edge of the frame; anything
        // ambiguous (a corner behind the lens, a straddling face) is kept and the shader
        // discards the pixels that miss.
        boolean allValid = true;
        boolean allLeft = true;
        boolean allRight = true;
        boolean allAbove = true;
        boolean allBelow = true;
        for (int i = 0; i < 4; i++) {
            float[] uv = project(m, origin, c[i].x, c[i].y, c[i].z);
            if (uv == null) {
                allValid = false;
                break;
            }
            allLeft &= uv[0] < 0.0F;
            allRight &= uv[0] > 1.0F;
            allAbove &= uv[1] < 0.0F;
            allBelow &= uv[1] > 1.0F;
        }
        if (allValid && (allLeft || allRight || allAbove || allBelow)) {
            return;
        }
        // Incidence shading, heavily softened: enough angle cue to read as light on geometry
        // without making grazing floors unreadable. The uv slots carry the face normal's x/y;
        // z rides along in slot 5's fractional packing below.
        Vec3 toLens = origin.subtract(c[0].add(c[2]).scale(0.5)).normalize();
        float lum = 0.78F + 0.22F * (float) Math.max(0.0, n.dot(toLens));
        float[] q = new float[24];
        for (int i = 0; i < 4; i++) {
            int o = i * 6;
            q[o] = (float) (c[i].x - anchor.getX());
            q[o + 1] = (float) (c[i].y - anchor.getY());
            q[o + 2] = (float) (c[i].z - anchor.getZ());
            q[o + 3] = (float) n.x;
            q[o + 4] = (float) n.y;
            q[o + 5] = lum * (n.z < 0.0 ? -1.0F : 1.0F);
        }
        quads.add(q);
    }

    private static Vec3[] faceCorners(BlockPos pos, Direction face, Vec3 n) {
        Vec3 centre = Vec3.atCenterOf(pos).add(n.scale(0.5 + SURFACE_EPSILON));
        Vec3 u;
        Vec3 v;
        if (face.getAxis() == Direction.Axis.Y) {
            u = new Vec3(0.5, 0, 0);
            v = new Vec3(0, 0, 0.5);
        } else {
            u = new Vec3(-n.z * 0.5, 0, n.x * 0.5);
            v = new Vec3(0, 0.5, 0);
        }
        return new Vec3[]{
                centre.subtract(u).subtract(v),
                centre.add(u).subtract(v),
                centre.add(u).add(v),
                centre.subtract(u).add(v),
        };
    }

    // ------------------------------------------------------------------ the visible beam

    private static void drawBeam(ProjectorBlockEntity be, PoseStack poseStack) {
        // Start at the chassis lens (a little below block centre, where the mesh's glass is),
        // and open at the frustum's own angles so the haze IS the light cone, just shorter.
        Vec3 origin = lensOrigin(be).add(0.0, -0.12, 0.0);
        Vec3 dir = lensDirection(be);
        Vec3 up = Math.abs(dir.y) > 0.999 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up).normalize();
        Vec3 upv = right.cross(dir).normalize();
        BlockPos anchor = be.getBlockPos();

        double len = Math.min(7.0, be.getFar() * 0.45);
        double tanV = Math.tan(Math.toRadians(be.getFov() * 0.5));
        double tanH = tanV * be.getAspect();
        float alpha = 0.10F * be.getBrightness();

        double near = 0.25;
        Vec3 nearC = origin.add(dir.scale(near));
        Vec3 farC = origin.add(dir.scale(len));
        Vec3 nr = right.scale(tanH * near);
        Vec3 nu = upv.scale(tanV * near);
        Vec3[] nearR = {nearC.subtract(nr).subtract(nu), nearC.add(nr).subtract(nu),
                nearC.add(nr).add(nu), nearC.subtract(nr).add(nu)};
        Vec3 r = right.scale(tanH * len);
        Vec3 u = upv.scale(tanV * len);
        Vec3[] farR = {farC.subtract(r).subtract(u), farC.add(r).subtract(u),
                farC.add(r).add(u), farC.subtract(r).add(u)};

        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        Matrix4f mat = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            beamVertex(builder, mat, anchor, nearR[i], alpha);
            beamVertex(builder, mat, anchor, nearR[j], alpha);
            beamVertex(builder, mat, anchor, farR[j], 0.0F);
            beamVertex(builder, mat, anchor, farR[i], 0.0F);
        }
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void beamVertex(BufferBuilder b, Matrix4f mat, BlockPos anchor, Vec3 v,
                                   float alpha) {
        b.vertex(mat, (float) (v.x - anchor.getX()), (float) (v.y - anchor.getY()),
                        (float) (v.z - anchor.getZ()))
                .color(alpha, alpha, alpha * 1.06F, 1.0F)
                .endVertex();
    }

    // ------------------------------------------------------------------ calibration frustum

    private static void drawFrustum(ProjectorBlockEntity be, PoseStack poseStack,
                                    MultiBufferSource buffers) {
        Vec3 origin = lensOrigin(be);
        Vec3 dir = lensDirection(be);
        Vec3 up = Math.abs(dir.y) > 0.999 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up).normalize();
        Vec3 upv = right.cross(dir).normalize();

        double tanV = Math.tan(Math.toRadians(be.getFov() * 0.5));
        double tanH = tanV * be.getAspect();
        BlockPos anchor = be.getBlockPos();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Matrix4f mat = poseStack.last().pose();

        Vec3[] nearC = frustumRect(origin, dir, right, upv, tanH, tanV, be.getNear(),
                be.getShiftH(), be.getShiftV());
        Vec3[] farC = frustumRect(origin, dir, right, upv, tanH, tanV, be.getFar(),
                be.getShiftH(), be.getShiftV());
        for (int i = 0; i < 4; i++) {
            line(lines, mat, anchor, nearC[i], nearC[(i + 1) % 4]);
            line(lines, mat, anchor, farC[i], farC[(i + 1) % 4]);
            line(lines, mat, anchor, nearC[i], farC[i]);
        }
        // Aim ray through the frame centre — the crosshair of the rig.
        line(lines, mat, anchor, origin, origin.add(dir.scale(be.getFar())));
    }

    private static Vec3[] frustumRect(Vec3 origin, Vec3 dir, Vec3 right, Vec3 up,
                                      double tanH, double tanV, double dist,
                                      float shiftH, float shiftV) {
        Vec3 centre = origin.add(dir.scale(dist))
                .add(right.scale(-shiftH * tanH * dist))
                .add(up.scale(-shiftV * tanV * dist));
        Vec3 r = right.scale(tanH * dist);
        Vec3 u = up.scale(tanV * dist);
        return new Vec3[]{
                centre.subtract(r).subtract(u),
                centre.add(r).subtract(u),
                centre.add(r).add(u),
                centre.subtract(r).add(u),
        };
    }

    private static void line(VertexConsumer lines, Matrix4f mat, BlockPos anchor, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a).normalize();
        lines.vertex(mat, (float) (a.x - anchor.getX()), (float) (a.y - anchor.getY()),
                        (float) (a.z - anchor.getZ()))
                .color(0.2F, 1.0F, 0.5F, 0.7F)
                .normal((float) d.x, (float) d.y, (float) d.z)
                .endVertex();
        lines.vertex(mat, (float) (b.x - anchor.getX()), (float) (b.y - anchor.getY()),
                        (float) (b.z - anchor.getZ()))
                .color(0.2F, 1.0F, 0.5F, 0.7F)
                .normal((float) d.x, (float) d.y, (float) d.z)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(ProjectorBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
