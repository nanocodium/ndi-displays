package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.WorklightBlockEntity;
import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Set;

/**
 * Draws the halogen work light from its part mesh: the yoke fixed under the ceiling, the
 * head tilted in it around the pivot axis, the R7s tube full-bright when the lamp is on,
 * the front glass translucent and warm.
 *
 * <p>Lighting the room is three things stacked: the block's own light level (LIT), a wide
 * warm flood through Extra Lights' volumetric beam when Theatrical is installed (a real 2 kW
 * halogen throws a visible shaft in stage haze), and a Shimmer coloured light on the floor
 * in front of it when Shimmer is present. The glow around the tube is an additive halo drawn
 * here, so the lamp blooms without any shader pack.
 */
public class WorklightRenderer implements BlockEntityRenderer<WorklightBlockEntity> {

    private static final ResourceLocation ATLAS =
            new ResourceLocation(NdiDisplays.MODID, "textures/block/worklight_atlas.png");

    /** The export is a 46 cm flood; a 2 kW stage halogen reads better at this scale. */
    private static final float SCALE = 1.6F;
    /** Top of the mount boss in mesh units: this touches the block above. */
    private static final float MOUNT_Y = 0.474F;
    /** Height of the pivot axis (X axis through pivot_0 / pivot_1) in mesh units. */
    private static final float PIVOT_Y = 0.26F;
    /** Centre of the R7s tube in mesh units, head space. */
    private static final float LAMP_Y = 0.26F;
    private static final float LAMP_Z = 0.01F;
    /** Front glass plane, mesh units, head space. */
    private static final float GLASS_Z = 0.083F;

    private static final float WARM_R = 1.0F;
    private static final float WARM_G = 0.93F;
    private static final float WARM_B = 0.78F;

    private static final float BEAM_LENGTH = 12.0F;
    private static final float FLOOD_HALF_ANGLE_DEG = 48.0F;

    private static final Set<String> YOKE = Set.of(
            "yoke_top", "yoke_arm_l", "yoke_arm_r", "pivot_0", "pivot_1",
            "knob_0", "knob_1", "knob_hub_0", "knob_hub_1",
            "mount_boss", "mount_hole", "mount_nut");

    private static boolean isBody(String part) {
        return !YOKE.contains(part) && !"front_glass".equals(part) && !"lamp_r7s".equals(part);
    }

    @Override
    public void render(WorklightBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        ObjPartMesh mesh = ObjPartMesh.get("worklight");
        boolean lit = be.isLit();
        Direction facing = be.getFacing();
        float tilt = be.getTilt();

        pose.pushPose();
        pose.translate(0.5, 1.0 - MOUNT_Y * SCALE, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(SCALE, SCALE, SCALE);

        VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(ATLAS));
        mesh.render(pose, solid, light, YOKE::contains);

        // Head: rotates in the yoke around the X axis through the pivots.
        pose.pushPose();
        pose.translate(0.0, PIVOT_Y, 0.0);
        pose.mulPose(Axis.XP.rotationDegrees(tilt));
        pose.translate(0.0, -PIVOT_Y, 0.0);

        mesh.render(pose, solid, light, WorklightRenderer::isBody);

        int lampLight = lit ? LightTexture.FULL_BRIGHT : light;
        if (lit) {
            mesh.render(pose, solid, lampLight, "lamp_r7s"::equals, 1.0F, 0.98F, 0.92F, 1.0F);
        } else {
            mesh.render(pose, solid, light, "lamp_r7s"::equals, 0.75F, 0.72F, 0.66F, 1.0F);
        }

        VertexConsumer glass = buffers.getBuffer(RenderType.entityTranslucent(ATLAS));
        if (lit) {
            mesh.render(pose, glass, LightTexture.FULL_BRIGHT, "front_glass"::equals,
                    WARM_R, WARM_G, WARM_B, 0.6F);
            drawHalo(pose, buffers.getBuffer(WorklightRenderTypes.GLOW));
        } else {
            mesh.render(pose, glass, light, "front_glass"::equals, 1.0F, 1.0F, 1.0F, 0.35F);
        }
        pose.popPose(); // head
        pose.popPose();

        if (lit) {
            lightTheRoom(be, facing, tilt);
        }
    }

    /**
     * Additive halo in head space: a bright disc on the glass and a wider, fainter one a
     * little in front, both fading to nothing at the rim. Reads as bloom on the lamp face.
     */
    private static void drawHalo(PoseStack pose, VertexConsumer vc) {
        Matrix4f m = pose.last().pose();
        disc(vc, m, 0.0F, LAMP_Y, GLASS_Z + 0.004F, 0.17F, 0.10F, 0.95F);
        disc(vc, m, 0.0F, LAMP_Y, GLASS_Z + 0.02F, 0.30F, 0.19F, 0.40F);
        disc(vc, m, 0.0F, LAMP_Y, GLASS_Z + 0.05F, 0.46F, 0.30F, 0.16F);
    }

    /** Elliptical fan (wider than tall, like the reflector) with alpha falling to the rim. */
    private static void disc(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz,
                             float rx, float ry, float alpha) {
        int segments = 20;
        int a0 = Math.round(alpha * 255.0F);
        for (int i = 0; i < segments; i++) {
            double t0 = (i / (double) segments) * Math.PI * 2.0;
            double t1 = ((i + 1) / (double) segments) * Math.PI * 2.0;
            float x0 = cx + (float) Math.cos(t0) * rx;
            float y0 = cy + (float) Math.sin(t0) * ry;
            float x1 = cx + (float) Math.cos(t1) * rx;
            float y1 = cy + (float) Math.sin(t1) * ry;
            glow(vc, m, cx, cy, cz, a0);
            glow(vc, m, x0, y0, cz, 0);
            glow(vc, m, x1, y1, cz, 0);
            glow(vc, m, x1, y1, cz, 0);
        }
    }

    private static void glow(VertexConsumer vc, Matrix4f m, float x, float y, float z, int a) {
        vc.vertex(m, x, y, z).color(255, 236, 196, a).endVertex();
    }

    /**
     * Volumetric flood through Extra Lights (when Theatrical is present) and a Shimmer light
     * on the floor in front. Head matrix is block-local with the beam along -Z, which is the
     * contract of {@link TheatricalCompat#submitFixtureBeam}.
     */
    private static void lightTheRoom(WorklightBlockEntity be, Direction facing, float tilt) {
        BlockPos pos = be.getBlockPos();
        PoseStack local = new PoseStack();
        local.translate(0.5, 1.0 - MOUNT_Y * SCALE, 0.5);
        local.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        local.scale(SCALE, SCALE, SCALE);
        local.translate(0.0, PIVOT_Y, 0.0);
        local.mulPose(Axis.XP.rotationDegrees(tilt));
        local.translate(0.0, -PIVOT_Y, 0.0);
        local.translate(0.0, LAMP_Y, GLASS_Z + 0.02F);
        local.mulPose(Axis.YP.rotationDegrees(180.0F)); // beam travels along the head's -Z
        Matrix4f head = new Matrix4f(local.last().pose());

        if (TheatricalCompat.LOADED) {
            // A halogen flood spreads about 50 degrees either side; volumetric only, the
            // classic flat cone would read as a beige sheet on the floor.
            TheatricalCompat.submitVolumetricFlood(pos, head, 0.16F * SCALE, FLOOD_HALF_ANGLE_DEG,
                    WARM_R, WARM_G, WARM_B, 0.85F, BEAM_LENGTH);
        }

        if (LedWallRenderer.SHIMMER_LOADED) {
            double yaw = Math.toRadians(-facing.toYRot());
            double t = Math.toRadians(tilt);
            double fx = 0.0, fy = -Math.sin(t), fz = Math.cos(t);
            double dx = fx * Math.cos(yaw) + fz * Math.sin(yaw);
            double dz = -fx * Math.sin(yaw) + fz * Math.cos(yaw);
            Vec3 origin = new Vec3(pos.getX() + head.m30(), pos.getY() + head.m31(), pos.getZ() + head.m32());
            Vec3 impact = origin.add(dx * 3.0, fy * 3.0, dz * 3.0);
            ShimmerSphereLights.update(pos, impact, WARM_R, WARM_G, WARM_B, 11.0F);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(WorklightBlockEntity be) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    /** Additive, depth-tested, no depth write: a glow that never punches holes in volumes. */
    static final class WorklightRenderTypes extends RenderStateShard {

        static final RenderType GLOW = RenderType.create(
                "ndidisplays_worklight_glow",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));

        private WorklightRenderTypes(String name, Runnable setup, Runnable clear) {
            super(name, setup, clear);
        }
    }

    @SuppressWarnings("unused")
    private static float clamp01(float v) {
        return Mth.clamp(v, 0.0F, 1.0F);
    }
}
