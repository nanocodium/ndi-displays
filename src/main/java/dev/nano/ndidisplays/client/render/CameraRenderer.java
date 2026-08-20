package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the camera rigs in broadcast-grade detail: ENG body with battery,
 * shoulder pad, viewfinder, matte box and lens rings; a single-arm broadcast
 * PTZ; jib truss boom with cabling and striped counterweights; track dolly
 * with wheel bogies. Live rigs show a red tally and a thin aim laser derived
 * from the same view state the NDI capture uses (beam == centre of the feed).
 * Static bases/tripods come from the block's JSON model. The PTZ is drawn
 * entirely here (the block uses ENTITYBLOCK_ANIMATED); the JSON is the
 * matching rest-pose silhouette for the inventory item.
 */
public class CameraRenderer implements BlockEntityRenderer<NdiCameraBlockEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(NdiDisplays.MODID, "textures/entity/camera_parts.png");

    // 8x8 material tiles in the 64x64 parts texture (index = col + row*8)
    private static final int BODY = 0;        // dark grey, panel seams + screws
    private static final int BODY_LIGHT = 1;  // brushed mid grey
    private static final int BLACK = 2;       // matte black
    private static final int LENS = 3;        // lens glass, highlight + fringe
    private static final int TALLY = 4;       // red glow
    private static final int VENT = 5;        // vent slits
    private static final int SILVER = 6;      // brushed metal
    private static final int GRIP = 7;        // rubber checker
    private static final int LCD = 8;         // status display
    private static final int CONNECTOR = 9;   // dark + gold pins
    private static final int CARBON = 10;     // carbon fibre weave
    private static final int HAZARD = 11;     // black/yellow stripes
    private static final int BLUE_LED = 12;   // power LED
    private static final int LABEL = 13;      // white label plate
    private static final int ORANGE = 14;     // accent
    private static final int CABLE = 15;      // cable rubber

    // Row 2: dedicated PTZ materials (richer graphite/glass/LED set)
    private static final int PTZ_BODY = 16;        // graphite panel, seam + screw
    private static final int PTZ_BODY_LIGHT = 17;  // brushed light grey
    private static final int PTZ_GLASS = 18;       // lens glass, cyan highlight + fringe
    private static final int PTZ_RING = 19;        // cyan LED ring
    private static final int PTZ_VENT = 20;        // vent slits on graphite
    private static final int PTZ_SILVER = 21;      // bright silver bezel
    private static final int PTZ_GLOSS = 22;       // piano-black barrel
    private static final int PTZ_IR = 23;          // IR window

    @Override
    public void render(NdiCameraBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        switch (be.getKind()) {
            case BROADCAST -> renderBroadcast(be, pose, vc, packedLight);
            case PTZ -> renderPtz(be, pose, vc, packedLight);
            case JIB -> renderJib(be, partialTick, pose, vc, packedLight);
            case TRACK -> renderTrack(be, partialTick, pose, vc, packedLight);
        }
    }

    // --- broadcast ENG camera --------------------------------------------

    private void renderBroadcast(NdiCameraBlockEntity be, PoseStack pose, VertexConsumer vc, int light) {
        pose.pushPose();
        pose.translate(0.5, 1.25, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(be.getFacing().toYRot() + be.getPan())));

        // fluid head under the body (stays level with pan, not tilt)
        box(pose, vc, light, -0.075F, -0.185F, -0.10F, 0.075F, -0.145F, 0.10F, SILVER);
        box(pose, vc, light, -0.055F, -0.145F, -0.075F, 0.055F, -0.115F, 0.075F, BLACK);
        // pan bar, angled back-down toward the operator
        pose.pushPose();
        pose.translate(0.06, -0.16, -0.08);
        pose.mulPose(Axis.XP.rotationDegrees(-35.0F));
        box(pose, vc, light, -0.008F, -0.008F, -0.30F, 0.008F, 0.008F, 0.0F, SILVER);
        box(pose, vc, light, -0.011F, -0.011F, -0.36F, 0.011F, 0.011F, -0.30F, GRIP);
        pose.popPose();

        pose.mulPose(Axis.XP.rotationDegrees(-be.getTilt()));
        // main body + carbon top plate
        box(pose, vc, light, -0.115F, -0.10F, -0.30F, 0.115F, 0.135F, 0.20F, BODY);
        box(pose, vc, light, -0.10F, 0.135F, -0.26F, 0.10F, 0.145F, 0.16F, CARBON);
        // shoulder pad
        box(pose, vc, light, -0.06F, -0.135F, -0.27F, 0.06F, -0.10F, -0.05F, GRIP);
        // battery pack with connector block
        box(pose, vc, light, -0.085F, -0.05F, -0.35F, 0.085F, 0.10F, -0.30F, CONNECTOR);
        box(pose, vc, light, -0.06F, -0.09F, -0.335F, 0.06F, -0.05F, -0.30F, BLACK);
        // left side: status LCD + button strip; right side: vents
        box(pose, vc, light, -0.121F, -0.02F, -0.20F, -0.115F, 0.075F, -0.02F, LCD);
        box(pose, vc, light, -0.121F, -0.065F, -0.20F, -0.115F, -0.03F, -0.06F, CONNECTOR);
        box(pose, vc, light, 0.115F, -0.05F, -0.22F, 0.121F, 0.08F, 0.04F, VENT);
        // white ID label on the rear right
        box(pose, vc, light, 0.115F, 0.09F, -0.24F, 0.1205F, 0.12F, -0.14F, LABEL);
        // top handle: posts, grip bar, accessory shoe
        box(pose, vc, light, -0.025F, 0.145F, -0.185F, 0.025F, 0.185F, -0.135F, BODY_LIGHT);
        box(pose, vc, light, -0.025F, 0.145F, 0.06F, 0.025F, 0.185F, 0.11F, BODY_LIGHT);
        box(pose, vc, light, -0.03F, 0.185F, -0.20F, 0.03F, 0.23F, 0.13F, GRIP);
        box(pose, vc, light, -0.02F, 0.23F, -0.05F, 0.02F, 0.245F, 0.05F, SILVER);
        // shotgun mic on the handle front
        box(pose, vc, light, -0.017F, 0.19F, 0.13F, 0.017F, 0.224F, 0.22F, BODY_LIGHT);
        box(pose, vc, light, -0.021F, 0.186F, 0.22F, 0.021F, 0.228F, 0.42F, GRIP);
        // viewfinder: arm, monitor with screen, rear eyepiece
        box(pose, vc, light, -0.19F, 0.065F, -0.05F, -0.115F, 0.095F, -0.01F, BODY_LIGHT);
        box(pose, vc, light, -0.225F, 0.01F, -0.09F, -0.185F, 0.15F, 0.03F, BLACK);
        box(pose, vc, light, -0.1855F, 0.03F, -0.07F, -0.181F, 0.13F, 0.01F, LCD);
        box(pose, vc, light, -0.215F, 0.03F, -0.15F, -0.195F, 0.11F, -0.09F, GRIP);
        // lens train: rear barrel, focus ring, silver ring, zoom ring, front barrel
        box(pose, vc, light, -0.065F, -0.05F, 0.20F, 0.065F, 0.08F, 0.245F, BLACK);
        box(pose, vc, light, -0.072F, -0.057F, 0.245F, 0.072F, 0.087F, 0.30F, GRIP);
        box(pose, vc, light, -0.062F, -0.047F, 0.30F, 0.062F, 0.077F, 0.322F, SILVER);
        box(pose, vc, light, -0.072F, -0.057F, 0.322F, 0.072F, 0.087F, 0.375F, GRIP);
        box(pose, vc, light, -0.06F, -0.045F, 0.375F, 0.06F, 0.075F, 0.41F, BLACK);
        // matte box with top flag
        box(pose, vc, light, -0.10F, -0.085F, 0.41F, 0.10F, 0.115F, 0.475F, BLACK);
        box(pose, vc, light, -0.095F, -0.08F, 0.474F, 0.095F, 0.11F, 0.478F, CARBON);
        box(pose, vc, light, -0.052F, -0.037F, 0.4735F, 0.052F, 0.067F, 0.4785F, LENS);
        pose.pushPose();
        pose.translate(0.0, 0.115, 0.43);
        pose.mulPose(Axis.XP.rotationDegrees(35.0F));
        box(pose, vc, light, -0.10F, 0.0F, 0.0F, 0.10F, 0.008F, 0.12F, CARBON);
        pose.popPose();
        // side flags, swung out at the same rake as the top one
        for (int sx = -1; sx <= 1; sx += 2) {
            pose.pushPose();
            pose.translate(0.10F * sx, 0.015, 0.43);
            pose.mulPose(Axis.YP.rotationDegrees(35.0F * sx));
            box(pose, vc, light, sx > 0 ? 0.0F : -0.008F, -0.095F, 0.0F,
                    sx > 0 ? 0.008F : 0.0F, 0.095F, 0.11F, CARBON);
            pose.popPose();
        }
        // lens control cable
        box(pose, vc, light, 0.066F, 0.0F, 0.10F, 0.078F, 0.012F, 0.30F, CABLE);
        if (be.isActive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.035F, 0.11F, -0.365F, 0.035F, 0.165F, -0.35F, TALLY);
        }
        box(pose, vc, LightTexture.FULL_BRIGHT, 0.055F, -0.03F, -0.356F, 0.075F, -0.01F, -0.35F, BLUE_LED);
        pose.popPose();
    }

    // --- single-arm broadcast PTZ (matches models/block/ptz_camera.json) ---

    private void renderPtz(NdiCameraBlockEntity be, PoseStack pose, VertexConsumer vc, int light) {
        float[] pt = be.getEasedPanTilt();
        boolean live = be.isActive();
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getFacing().toYRot()));

        // Rubber feet tucked under a piano-black stepped puck (no 45° star).
        for (int i = 0; i < 4; i++) {
            float a = (float) Math.toRadians(45 + i * 90);
            float fx = (float) Math.cos(a) * 0.132F;
            float fz = (float) Math.sin(a) * 0.132F;
            box(pose, vc, light, fx - 0.016F, 0.000F, fz - 0.016F, fx + 0.016F, 0.012F, fz + 0.016F, GRIP);
        }
        box(pose, vc, light, -0.168F, 0.008F, -0.168F, 0.168F, 0.022F, 0.168F, BLACK);
        box(pose, vc, light, -0.158F, 0.022F, -0.158F, 0.158F, 0.148F, 0.158F, PTZ_GLOSS);
        box(pose, vc, light, -0.146F, 0.148F, -0.146F, 0.146F, 0.172F, 0.146F, PTZ_BODY);
        box(pose, vc, light, -0.132F, 0.172F, -0.132F, 0.132F, 0.198F, 0.132F, BLACK);

        // Flush side vents + compact rear I/O.
        box(pose, vc, light, 0.154F, 0.048F, -0.042F, 0.162F, 0.128F, 0.042F, PTZ_VENT);
        box(pose, vc, light, -0.162F, 0.048F, -0.042F, -0.154F, 0.128F, 0.042F, PTZ_VENT);
        box(pose, vc, light, -0.048F, 0.046F, -0.166F, 0.048F, 0.132F, -0.154F, PTZ_BODY);
        box(pose, vc, light, -0.038F, 0.088F, -0.174F, -0.008F, 0.118F, -0.162F, CONNECTOR);
        box(pose, vc, light, 0.004F, 0.090F, -0.174F, 0.020F, 0.114F, -0.162F, CONNECTOR);
        box(pose, vc, light, 0.024F, 0.056F, -0.178F, 0.040F, 0.082F, -0.160F, CABLE);

        // Front: IR strip + two tiny status LEDs, inset in the face.
        box(pose, vc, light, -0.038F, 0.108F, 0.154F, 0.038F, 0.132F, 0.164F, PTZ_IR);
        box(pose, vc, light, -0.028F, 0.078F, 0.154F, 0.028F, 0.100F, 0.160F, LABEL);
        box(pose, vc, live ? LightTexture.FULL_BRIGHT : light,
                -0.030F, 0.048F, 0.154F, -0.010F, 0.068F, 0.162F, live ? TALLY : PTZ_BODY);
        box(pose, vc, LightTexture.FULL_BRIGHT, 0.010F, 0.048F, 0.154F, 0.030F, 0.068F, 0.162F, BLUE_LED);

        // Pan platter — thin black disc, no glow ring, no silver star.
        pose.mulPose(Axis.YP.rotationDegrees(-pt[0]));
        box(pose, vc, light, -0.118F, 0.196F, -0.118F, 0.118F, 0.224F, 0.118F, PTZ_GLOSS);
        box(pose, vc, light, -0.036F, 0.222F, -0.036F, 0.036F, 0.236F, 0.036F, PTZ_BODY);

        box(pose, vc, light, -0.188F, 0.218F, -0.048F, -0.098F, 0.268F, 0.048F, PTZ_BODY);
        octoYAt(pose, vc, light, -0.148F, 0.036F, 0.260F, 0.482F, PTZ_BODY);
        box(pose, vc, light, -0.184F, 0.455F, -0.061F, -0.038F, 0.555F, 0.061F, PTZ_BODY);
        octoX(pose, vc, light, -0.195F, -0.168F, 0.500F, 0.044F, PTZ_SILVER);
        box(pose, vc, light, -0.198F, 0.478F, -0.022F, -0.186F, 0.522F, 0.022F, PTZ_GLOSS);

        // Tilting head — body, rear I/O, shoe, lens train. Pivot = capture eye height.
        pose.translate(0.0, 0.500F, 0.0);
        pose.mulPose(Axis.XP.rotationDegrees(-pt[1]));
        box(pose, vc, light, -0.101F, -0.099F, -0.116F, 0.151F, 0.080F, 0.089F, PTZ_BODY);
        box(pose, vc, light, -0.086F, -0.086F, -0.170F, 0.136F, 0.068F, -0.112F, PTZ_GLOSS);
        box(pose, vc, light, -0.053F, -0.053F, -0.180F, 0.103F, 0.034F, -0.168F, CONNECTOR);
        box(pose, vc, light, 0.132F, -0.028F, -0.055F, 0.148F, 0.028F, 0.040F, PTZ_VENT);
        box(pose, vc, light, -0.041F, 0.076F, -0.053F, 0.041F, 0.093F, 0.034F, PTZ_SILVER);
        box(pose, vc, light, -0.025F, 0.090F, -0.028F, 0.025F, 0.099F, 0.016F, BLACK);

        box(pose, vc, light, -0.080F, -0.080F, 0.086F, 0.130F, 0.061F, 0.136F, PTZ_BODY_LIGHT);
        box(pose, vc, light, -0.070F, -0.070F, 0.134F, 0.120F, 0.051F, 0.184F, GRIP);
        box(pose, vc, light, -0.064F, -0.064F, 0.182F, 0.114F, 0.045F, 0.205F, PTZ_SILVER);
        box(pose, vc, light, -0.051F, -0.051F, 0.203F, 0.101F, 0.033F, 0.232F, BLACK);
        box(pose, vc, light, -0.039F, -0.039F, 0.230F, 0.089F, 0.020F, 0.242F, PTZ_GLASS);
        box(pose, vc, LightTexture.FULL_BRIGHT, -0.012F, -0.008F, 0.240F, 0.028F, 0.012F, 0.246F, LENS);

        if (live) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.050F, 0.074F, 0.034F, 0.050F, 0.095F, 0.084F, TALLY);
        }
        pose.popPose();
    }

    // --- octagon helpers (box + the same box rotated 45° about the axis) ---

    /** Octagonal drum about the vertical axis (pan platter, caps). */
    private static void octoY(PoseStack pose, VertexConsumer vc, int light,
                              float half, float y0, float y1, int tile) {
        box(pose, vc, light, -half, y0, -half, half, y1, half, tile);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(45.0F));
        box(pose, vc, light, -half, y0, -half, half, y1, half, tile);
        pose.popPose();
    }

    /** {@link #octoY} centred on {@code (cx, 0, 0)} — used for the PTZ arm pillar. */
    private static void octoYAt(PoseStack pose, VertexConsumer vc, int light,
                                float cx, float half, float y0, float y1, int tile) {
        pose.pushPose();
        pose.translate(cx, 0.0, 0.0);
        octoY(pose, vc, light, half, y0, y1, tile);
        pose.popPose();
    }

    /** Octagonal disc about the X axis, centred at height {@code cy} (tilt pivots). */
    private static void octoX(PoseStack pose, VertexConsumer vc, int light,
                              float x0, float x1, float cy, float half, int tile) {
        pose.pushPose();
        pose.translate(0.0, cy, 0.0);
        box(pose, vc, light, x0, -half, -half, x1, half, half, tile);
        pose.mulPose(Axis.XP.rotationDegrees(45.0F));
        box(pose, vc, light, x0, -half, -half, x1, half, half, tile);
        pose.popPose();
    }

    /** Octagonal barrel about the Z axis, centred on the lens axis (lens rings). */
    private static void octoZ(PoseStack pose, VertexConsumer vc, int light,
                              float z0, float z1, float half, int tile) {
        box(pose, vc, light, -half, -half, z0, half, half, z1, tile);
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
        box(pose, vc, light, -half, -half, z0, half, half, z1, tile);
        pose.popPose();
    }

    // --- jib boom ---------------------------------------------------------

    private void renderJib(NdiCameraBlockEntity be, float partialTick, PoseStack pose, VertexConsumer vc, int light) {
        float[] arm = be.getJibArmAngles(partialTick);
        float len = be.getJibArmLength();
        pose.pushPose();
        pose.translate(0.5, 1.05, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-arm[0]));
        // pivot block with control LCD
        box(pose, vc, light, -0.07F, -0.09F, -0.09F, 0.07F, 0.09F, 0.09F, BODY);
        box(pose, vc, light, -0.071F, -0.05F, -0.05F, -0.0695F, 0.05F, 0.05F, LCD);
        pose.mulPose(Axis.XP.rotationDegrees(-arm[1]));
        // twin-chord truss boom with cross braces + cable run
        box(pose, vc, light, -0.055F, 0.02F, -0.9F, -0.02F, 0.055F, len, SILVER);
        box(pose, vc, light, 0.02F, 0.02F, -0.9F, 0.055F, 0.055F, len, SILVER);
        box(pose, vc, light, -0.055F, -0.055F, -0.9F, -0.02F, -0.02F, len, SILVER);
        box(pose, vc, light, 0.02F, -0.055F, -0.9F, 0.055F, -0.02F, len, SILVER);
        for (float z = -0.7F; z < len - 0.1F; z += 0.7F) {
            box(pose, vc, light, -0.05F, -0.02F, z, 0.05F, 0.02F, z + 0.045F, BODY_LIGHT);
        }
        box(pose, vc, light, -0.008F, 0.058F, -0.85F, 0.008F, 0.072F, len - 0.05F, CABLE);
        // counterweight stack with hazard stripes
        box(pose, vc, light, -0.17F, -0.22F, -1.10F, 0.17F, 0.17F, -1.045F, HAZARD);
        box(pose, vc, light, -0.155F, -0.205F, -1.045F, 0.155F, 0.155F, -0.945F, BLACK);
        box(pose, vc, light, -0.125F, -0.17F, -0.875F, 0.125F, 0.12F, -0.805F, BODY);
        pose.popPose();

        // --- operator's seat, behind the pivot on the counterweight side ---
        //
        // Drawn outside the boom's tilt so the seat stays level however the arm is flown — an
        // operator's chair on a crane is gimballed, and a seat that pitched with the boom would
        // read as broken. It follows the arm's yaw only, which is what makes it visibly the
        // thing a player mounts.
        pose.pushPose();
        pose.translate(0.0, -0.35, -1.6);
        // pan (seat pad), backrest and the post carrying them
        box(pose, vc, light, -0.17F, 0.0F, -0.16F, 0.17F, 0.045F, 0.16F, GRIP);
        box(pose, vc, light, -0.165F, 0.045F, -0.175F, 0.165F, 0.32F, -0.13F, GRIP);
        box(pose, vc, light, -0.05F, -0.30F, -0.05F, 0.05F, 0.0F, 0.05F, SILVER);
        box(pose, vc, light, -0.12F, -0.34F, -0.12F, 0.12F, -0.30F, 0.12F, BODY);
        // armrest-mounted control panel: joystick box and a small readout
        box(pose, vc, light, 0.10F, 0.045F, 0.02F, 0.20F, 0.12F, 0.16F, BODY);
        box(pose, vc, light, 0.13F, 0.12F, 0.06F, 0.17F, 0.19F, 0.10F, SILVER);
        box(pose, vc, light, -0.20F, 0.045F, 0.02F, -0.10F, 0.12F, 0.16F, BODY);
        box(pose, vc, light, -0.185F, 0.121F, 0.05F, -0.115F, 0.125F, 0.13F, LCD);
        // footplate, so the seat reads as somewhere a person sits rather than a floating box
        box(pose, vc, light, -0.15F, -0.30F, 0.16F, 0.15F, -0.26F, 0.34F, CARBON);
        pose.popPose();

        // camera head hangs from the boom tip on a yoke with a pan motor
        Vec3 tip = Vec3.directionFromRotation(-arm[1], arm[0]).scale(len);
        pose.pushPose();
        pose.translate(0.5 + tip.x, 1.05 + tip.y, 0.5 + tip.z);
        pose.mulPose(Axis.YP.rotationDegrees(-(arm[0] + be.getPan())));
        box(pose, vc, light, -0.035F, -0.055F, -0.035F, 0.035F, 0.02F, 0.035F, BODY_LIGHT);
        box(pose, vc, light, -0.02F, -0.115F, -0.02F, 0.02F, -0.055F, 0.02F, SILVER);
        pose.translate(0.0, -0.175, 0.0);
        pose.mulPose(Axis.XP.rotationDegrees(-be.getTilt()));
        // compact remote head
        box(pose, vc, light, -0.085F, -0.062F, -0.16F, 0.085F, 0.075F, 0.10F, BODY);
        box(pose, vc, light, -0.086F, -0.03F, -0.10F, -0.0845F, 0.04F, 0.0F, VENT);
        box(pose, vc, light, 0.0845F, -0.03F, -0.10F, 0.086F, 0.04F, 0.0F, VENT);
        box(pose, vc, light, -0.05F, -0.04F, 0.10F, 0.05F, 0.055F, 0.15F, GRIP);
        box(pose, vc, light, -0.044F, -0.034F, 0.15F, 0.044F, 0.049F, 0.185F, BLACK);
        box(pose, vc, light, -0.036F, -0.026F, 0.183F, 0.036F, 0.041F, 0.188F, LENS);
        box(pose, vc, light, -0.02F, 0.075F, -0.06F, 0.02F, 0.088F, 0.0F, CABLE);
        if (be.isActive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.022F, -0.005F, -0.172F, 0.022F, 0.04F, -0.16F, TALLY);
        }
        pose.popPose();
    }

    // --- track dolly ------------------------------------------------------

    /**
     * Motion-control camera robot on rails, in the mould of a Mark Roberts MILO: a tracked
     * dolly carrying a slewing pedestal, a boom raked back over it on hydraulic rams, and a
     * cranked arm reaching forward to a pan/tilt head.
     *
     * The chassis follows the rail heading so the bogies stay square to the track through
     * curves, while everything above the turntable slews with pan — that is what gives the rig
     * its reach, and why {@link NdiCameraBlockEntity} derives the eye from the same offsets
     * rather than a point above the deck.
     */
    /** Wheel radius, so the tyres sit on the rail instead of the chassis floor. */
    private static final float WHEEL_DROP = 0.02F;

    private void renderTrack(NdiCameraBlockEntity be, float partialTick, PoseStack pose, VertexConsumer vc, int light) {
        Vec3 dolly = be.getDollyPos(partialTick).subtract(Vec3.atLowerCornerOf(be.getBlockPos()));
        float baseYaw = be.getDollyYaw(partialTick);
        pose.pushPose();
        // The dolly position is the rail's top surface, so the rig is dropped by the wheel radius
        // to put the tyres *on* the rail rather than resting the chassis floor on it. Without
        // this the whole machine reads as hovering a few centimetres clear of the track.
        pose.translate(dolly.x, dolly.y - WHEEL_DROP, dolly.z);
        pose.mulPose(Axis.YP.rotationDegrees(-baseYaw));

        // --- tracked dolly: heavy chassis, bogies and rubber wheels on the rail ---
        box(pose, vc, light, -0.30F, 0.045F, -0.30F, 0.30F, 0.10F, 0.30F, BODY);
        box(pose, vc, light, -0.30F, 0.10F, -0.30F, 0.30F, 0.115F, 0.30F, CARBON);
        box(pose, vc, light, -0.305F, 0.055F, -0.305F, 0.305F, 0.08F, -0.295F, HAZARD);
        box(pose, vc, light, -0.305F, 0.055F, 0.295F, 0.305F, 0.08F, 0.305F, HAZARD);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float cx = 0.205F * sx;
                float cz = 0.20F * sz;
                box(pose, vc, light, cx - 0.06F, 0.0F, cz - 0.055F, cx + 0.06F, 0.05F, cz - 0.045F, SILVER);
                box(pose, vc, light, cx - 0.06F, 0.0F, cz + 0.045F, cx + 0.06F, 0.05F, cz + 0.055F, SILVER);
                box(pose, vc, light, cx - 0.05F, -0.02F, cz - 0.043F, cx + 0.05F, 0.055F, cz + 0.043F, GRIP);
            }
        }
        // drive box and cable drum hanging off the side of the chassis
        box(pose, vc, light, 0.30F, 0.06F, -0.09F, 0.375F, 0.15F, 0.09F, CONNECTOR);
        box(pose, vc, light, -0.28F, 0.115F, 0.16F, -0.12F, 0.145F, 0.26F, CABLE);

        // --- slewing ring, then everything above it turns with pan ---
        pose.translate(0.0, 0.115, 0.0);
        box(pose, vc, light, -0.20F, 0.0F, -0.20F, 0.20F, 0.035F, 0.20F, SILVER);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getPan()));

        // --- pedestal: wide skirt tapering into the tower, as on the real machine ---
        box(pose, vc, light, -0.185F, 0.035F, -0.185F, 0.185F, 0.10F, 0.185F, BODY);
        box(pose, vc, light, -0.155F, 0.10F, -0.155F, 0.155F, 0.26F, 0.155F, BODY);
        box(pose, vc, light, -0.125F, 0.26F, -0.125F, 0.125F, 0.42F, 0.125F, BODY_LIGHT);
        // service panels and the branding plate down one flank
        box(pose, vc, light, 0.125F, 0.14F, -0.06F, 0.131F, 0.34F, 0.10F, LABEL);
        box(pose, vc, light, -0.131F, 0.12F, -0.05F, -0.125F, 0.30F, 0.09F, VENT);
        // electronics cabinet on the back of the pedestal
        box(pose, vc, light, -0.11F, 0.09F, -0.29F, 0.11F, 0.30F, -0.155F, CONNECTOR);
        box(pose, vc, light, -0.07F, 0.14F, -0.296F, 0.07F, 0.25F, -0.29F, LCD);

        // --- telescoping column: fills the gap when the rig is raised off its stock stance.
        // Nested square sections, each thinner than the one below, so it reads as a real
        // telescope rather than a stretched pedestal. Everything above rides up with it.
        float ext = be.getTrackColumn();
        if (ext > 0.01F) {
            float seg = ext / 3.0F;
            float base = 0.42F;
            box(pose, vc, light, -0.115F, base, -0.115F, 0.115F, base + seg + 0.02F, 0.115F, BODY);
            box(pose, vc, light, -0.098F, base + seg, -0.098F, 0.098F, base + 2 * seg + 0.02F, 0.098F, BODY_LIGHT);
            box(pose, vc, light, -0.082F, base + 2 * seg, -0.082F, 0.082F, base + ext, 0.082F, SILVER);
            // collar clamps at each overlap, like the locking rings on a real column
            box(pose, vc, light, -0.105F, base + seg - 0.015F, -0.105F, 0.105F, base + seg + 0.015F, 0.105F, BLACK);
            box(pose, vc, light, -0.090F, base + 2 * seg - 0.015F, -0.090F, 0.090F, base + 2 * seg + 0.015F, 0.090F, BLACK);
        }
        pose.translate(0.0, ext, 0.0);

        // --- boom: raked back over the pedestal, carried on two rams ---
        pose.pushPose();
        pose.translate(0.0, 0.42, 0.0);
        pose.mulPose(Axis.XP.rotationDegrees(-34.0F));
        box(pose, vc, light, -0.115F, -0.02F, -0.05F, 0.115F, 0.055F, 0.46F, BODY);
        box(pose, vc, light, -0.10F, 0.055F, 0.02F, 0.10F, 0.068F, 0.42F, CARBON);
        // rams either side, angled into the boom
        for (int sx = -1; sx <= 1; sx += 2) {
            float cx = 0.135F * sx;
            box(pose, vc, light, cx - 0.018F, -0.14F, 0.05F, cx + 0.018F, -0.02F, 0.30F, SILVER);
            box(pose, vc, light, cx - 0.012F, -0.20F, 0.02F, cx + 0.012F, -0.13F, 0.14F, BLACK);
        }
        pose.popPose();

        // --- cranked arm: forward along the top, then down to the head ---
        pose.translate(0.0, 0.80, 0.0);
        box(pose, vc, light, -0.085F, -0.05F, -0.02F, 0.085F, 0.05F, 0.30F, BODY);
        box(pose, vc, light, -0.075F, -0.045F, 0.30F, 0.075F, 0.045F, 0.62F, BODY_LIGHT);
        box(pose, vc, light, -0.06F, 0.05F, 0.06F, 0.06F, 0.062F, 0.56F, CARBON);
        // knuckle at the bend, and the drop to the head
        box(pose, vc, light, -0.08F, -0.075F, 0.56F, 0.08F, 0.06F, 0.70F, BODY);
        box(pose, vc, light, -0.05F, -0.20F, 0.60F, 0.05F, -0.07F, 0.68F, SILVER);
        // cable run taped along the arm, the giveaway that it is a working machine
        box(pose, vc, light, 0.085F, -0.02F, 0.04F, 0.10F, 0.005F, 0.58F, CABLE);

        // --- pan/tilt head on the end of the arm ---
        pose.translate(0.0, -0.22, 0.64);
        box(pose, vc, light, -0.065F, -0.03F, -0.05F, 0.065F, 0.02F, 0.05F, SILVER);
        pose.mulPose(Axis.XP.rotationDegrees(-be.getTilt()));
        // camera body, side vents, rear display
        box(pose, vc, light, -0.085F, -0.075F, -0.16F, 0.085F, 0.075F, 0.10F, BODY);
        box(pose, vc, light, -0.091F, -0.05F, -0.10F, -0.085F, 0.05F, 0.02F, VENT);
        box(pose, vc, light, 0.085F, -0.05F, -0.10F, 0.091F, 0.05F, 0.02F, VENT);
        box(pose, vc, light, -0.055F, -0.045F, -0.166F, 0.055F, 0.055F, -0.16F, LCD);
        // follow-focus motor: servo body on the barrel with an orange drive ring
        box(pose, vc, light, 0.055F, -0.02F, 0.115F, 0.098F, 0.045F, 0.185F, SILVER);
        box(pose, vc, light, 0.06F, -0.035F, 0.132F, 0.093F, -0.02F, 0.168F, ORANGE);
        // matte box, lens barrel and glass
        box(pose, vc, light, -0.07F, -0.06F, 0.10F, 0.07F, 0.06F, 0.15F, GRIP);
        box(pose, vc, light, -0.055F, -0.05F, 0.15F, 0.055F, 0.05F, 0.20F, BLACK);
        box(pose, vc, light, -0.075F, -0.07F, 0.20F, 0.075F, 0.07F, 0.225F, BLACK);
        box(pose, vc, light, -0.045F, -0.04F, 0.223F, 0.045F, 0.04F, 0.228F, LENS);
        if (be.isActive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.02F, 0.075F, -0.10F, 0.02F, 0.088F, -0.05F, TALLY);
        }
        pose.popPose();
    }

    // --- geometry helpers -------------------------------------------------

    /** Draws a cuboid; every face maps the full 8x8 tile so texture detail shows. */
    private static void box(PoseStack pose, VertexConsumer vc, int light,
                            float x0, float y0, float z0, float x1, float y1, float z1, int tile) {
        float u0 = ((tile % 8) * 8 + 0.5F) / 64.0F;
        float u1 = ((tile % 8) * 8 + 7.5F) / 64.0F;
        float v0 = ((tile / 8) * 8 + 0.5F) / 64.0F;
        float v1 = ((tile / 8) * 8 + 7.5F) / 64.0F;
        PoseStack.Pose p = pose.last();
        quad(p, vc, light, u0, v0, u1, v1, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0);
        quad(p, vc, light, u0, v0, u1, v1, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0);
        quad(p, vc, light, u0, v0, u1, v1, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0, 0, 0, -1);
        quad(p, vc, light, u0, v0, u1, v1, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0, 0, 1);
        quad(p, vc, light, u0, v0, u1, v1, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1, 0, 0);
        quad(p, vc, light, u0, v0, u1, v1, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, 1, 0, 0);
    }

    private static void quad(PoseStack.Pose p, VertexConsumer vc, int light,
                             float u0, float v0, float u1, float v1,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz) {
        vertex(p, vc, light, ax, ay, az, u0, v1, nx, ny, nz);
        vertex(p, vc, light, bx, by, bz, u1, v1, nx, ny, nz);
        vertex(p, vc, light, cx, cy, cz, u1, v0, nx, ny, nz);
        vertex(p, vc, light, dx, dy, dz, u0, v0, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer vc, int light,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz) {
        vc.vertex(p.pose(), x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(p.normal(), nx, ny, nz)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(NdiCameraBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
