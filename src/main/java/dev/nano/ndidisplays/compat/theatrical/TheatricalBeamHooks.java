package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.imabad.theatrical.client.LazyRenderers;
import dev.imabad.theatrical.client.TheatricalRenderTypes;
import dev.imabad.theatrical.config.TheatricalConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Client-side bridge into Theatrical's beam pipeline, so a fixture flown from a
 * kinetic winch emits the same light as one bolted to a truss. Only classloaded when
 * Theatrical is present (see {@link TheatricalCompat}).
 *
 * Mirrors the non-raymarch path of Theatrical's {@code FixtureRenderer}: the beam is
 * queued on {@link LazyRenderers} (drawn after the world, far-to-near for correct
 * translucency) through {@link TheatricalRenderTypes#BEAM}, with the classic
 * four-quad flare geometry and the user's beamOpacity config applied.
 */
final class TheatricalBeamHooks {

    private TheatricalBeamHooks() {
    }

    /**
     * Queues one beam for this frame. {@code headMatrix} is the head transform in
     * block-local space (identity stack, beam start included); the lazy renderer
     * re-anchors it on the fixture position camera-relatively like Theatrical does.
     */
    static boolean submitBeam(BlockPos fixturePos, Matrix4f headMatrix, float beamWidth,
                              float focus01, float r, float g, float b,
                              float intensity01, float length) {
        float beamOpacity = (float) TheatricalConfig.INSTANCE.CLIENT.beamOpacity;
        float alpha = Math.min(1.0F, intensity01 * beamOpacity);
        if (alpha <= 0.003F) {
            return true;
        }
        Matrix4f head = new Matrix4f(headMatrix);
        Vec3 headWorld = Vec3.atLowerCornerOf(fixturePos)
                .add(head.m30(), head.m31(), head.m32());

        LazyRenderers.addLazyRender(new LazyRenderers.LazyRenderer() {
            @Override
            public void render(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack,
                               Camera camera, float partialTick) {
                poseStack.pushPose();
                Vec3 offset = Vec3.atLowerCornerOf(fixturePos).subtract(camera.getPosition());
                poseStack.translate(offset.x, offset.y, offset.z);
                poseStack.mulPoseMatrix(head);
                VertexConsumer vc = bufferSource.getBuffer(TheatricalRenderTypes.BEAM);
                drawLens(vc, poseStack.last().pose(), beamWidth,
                        (int) (r * 255), (int) (g * 255), (int) (b * 255),
                        (int) (Math.min(1.0F, intensity01) * 255));
                drawBeam(vc, poseStack.last().pose(), beamWidth, focus01, length,
                        (int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (alpha * 255));
                poseStack.popPose();
            }

            @Override
            public Vec3 getPos(float partialTick) {
                return headWorld;
            }
        });
        return true;
    }

    /**
     * The coloured emissive face of the head, drawn at the beam start — this is what
     * makes the DMX colour readable on the fixture body itself, exactly like the lens
     * quad Theatrical's own renderers push through the beam type.
     */
    private static void drawLens(VertexConsumer vc, Matrix4f m, float beamSize,
                                 int r, int g, int b, int a) {
        float s = Math.max(beamSize, 0.13F);
        vertex(vc, m, r, g, b, a, -s, -s, 0.01F);
        vertex(vc, m, r, g, b, a, -s, s, 0.01F);
        vertex(vc, m, r, g, b, a, s, s, 0.01F);
        vertex(vc, m, r, g, b, a, s, -s, 0.01F);
        vertex(vc, m, r, g, b, a, s, -s, 0.01F);
        vertex(vc, m, r, g, b, a, s, s, 0.01F);
        vertex(vc, m, r, g, b, a, -s, s, 0.01F);
        vertex(vc, m, r, g, b, a, -s, -s, 0.01F);
    }

    /**
     * Theatrical's classic beam: four flaring quads along -Z, fading out at the end.
     * The flare formula matches theirs exactly — they feed the RAW focus byte (0-255)
     * into {@code 1 + focus*len*0.03}, which is why focus visibly opens the cone on a
     * truss fixture; a 0-1 focus here would read as no effect at all.
     */
    private static void drawBeam(VertexConsumer vc, Matrix4f m, float beamSize, float focus,
                                 float length, int r, int g, int b, int a) {
        float len = length + 0.5F;
        float endMul = 1.0F + focus * 255.0F * len * 0.03F;

        vertex(vc, m, r, g, b, 0, beamSize * endMul, beamSize * endMul, -len);
        vertex(vc, m, r, g, b, a, beamSize, beamSize, 0);
        vertex(vc, m, r, g, b, a, beamSize, -beamSize, 0);
        vertex(vc, m, r, g, b, 0, beamSize * endMul, -beamSize * endMul, -len);

        vertex(vc, m, r, g, b, 0, -beamSize * endMul, -beamSize * endMul, -len);
        vertex(vc, m, r, g, b, a, -beamSize, -beamSize, 0);
        vertex(vc, m, r, g, b, a, -beamSize, beamSize, 0);
        vertex(vc, m, r, g, b, 0, -beamSize * endMul, beamSize * endMul, -len);

        vertex(vc, m, r, g, b, 0, -beamSize * endMul, beamSize * endMul, -len);
        vertex(vc, m, r, g, b, a, -beamSize, beamSize, 0);
        vertex(vc, m, r, g, b, a, beamSize, beamSize, 0);
        vertex(vc, m, r, g, b, 0, beamSize * endMul, beamSize * endMul, -len);

        vertex(vc, m, r, g, b, 0, beamSize * endMul, -beamSize * endMul, -len);
        vertex(vc, m, r, g, b, a, beamSize, -beamSize, 0);
        vertex(vc, m, r, g, b, a, -beamSize, -beamSize, 0);
        vertex(vc, m, r, g, b, 0, -beamSize * endMul, -beamSize * endMul, -len);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, int r, int g, int b, int a,
                               float x, float y, float z) {
        vc.vertex(m, x, y, z).color(r, g, b, a).endVertex();
    }
}
