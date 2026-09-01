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
            case BROADCAST -> renderBroadcast(be, pose, buffers, packedLight);
            case PTZ -> renderPtz(be, pose, buffers, packedLight);
            case JIB -> renderJib(be, partialTick, pose, vc, packedLight);
            case TRACK -> renderTrack(be, partialTick, pose, vc, packedLight);
        }
    }

    // --- broadcast ENG camera --------------------------------------------

    private static final net.minecraft.resources.ResourceLocation BROADCAST_ATLAS =
            new net.minecraft.resources.ResourceLocation(NdiDisplays.MODID,
                    "textures/entity/broadcast_camera_atlas.png");
    private static final net.minecraft.resources.ResourceLocation PTZ_ATLAS =
            new net.minecraft.resources.ResourceLocation(NdiDisplays.MODID,
                    "textures/entity/ptz_camera_atlas.png");

    /**
     * The generated ENG-camera mesh. Three articulation layers, exactly the fluid-head reality:
     * the tripod stands still with the block's facing, the head and pan bars swing with pan, and
     * the camera body with everything bolted to it tips with tilt around the head drum's axis.
     */
    private void renderBroadcast(NdiCameraBlockEntity be, PoseStack pose,
                                 MultiBufferSource buffers, int light) {
        ObjPartMesh mesh = ObjPartMesh.get("broadcast_camera");
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(BROADCAST_ATLAS));
        boolean live = be.isActive();

        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getFacing().toYRot()));
        mesh.render(pose, vc, light,
                g -> g.startsWith("tripod") || g.startsWith("spreader"));

        pose.mulPose(Axis.YP.rotationDegrees(-be.getPan()));
        mesh.render(pose, vc, light, g -> g.startsWith("head_") || g.startsWith("pan_"));

        // tilt about the head drum axis
        pose.translate(0.0, 0.975, -0.1134);
        pose.mulPose(Axis.XP.rotationDegrees(-be.getTilt()));
        pose.translate(0.0, -0.975, 0.1134);
        mesh.render(pose, vc, light, g -> !g.startsWith("tripod") && !g.startsWith("spreader")
                && !g.startsWith("head_") && !g.startsWith("pan_")
                && !g.equals("tally_lamp") && !g.equals("battery_led") && !g.equals("side_led"));
        mesh.render(pose, vc, live ? LightTexture.FULL_BRIGHT : light,
                g -> g.equals("tally_lamp"));
        mesh.render(pose, vc, LightTexture.FULL_BRIGHT,
                g -> g.equals("battery_led") || g.equals("side_led"));
        pose.popPose();
    }

    /**
     * The generated PTZ mesh: the base and its rear I/O hold the block's facing, the turntable
     * and yoke pan, and the head barrel tilts around the yoke's axle.
     */
    private void renderPtz(NdiCameraBlockEntity be, PoseStack pose,
                           MultiBufferSource buffers, int light) {
        ObjPartMesh mesh = ObjPartMesh.get("ptz_camera");
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(PTZ_ATLAS));
        float[] pt = be.getEasedPanTilt();
        boolean live = be.isActive();

        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getFacing().toYRot()));
        mesh.render(pose, vc, light, g -> g.startsWith("base_") && !g.equals("base_turntable")
                || g.startsWith("port_") || g.equals("rear_panel"));
        mesh.render(pose, vc, live ? LightTexture.FULL_BRIGHT : light,
                g -> g.equals("base_led"));

        pose.mulPose(Axis.YP.rotationDegrees(-pt[0]));
        mesh.render(pose, vc, light,
                g -> g.equals("base_turntable") || g.startsWith("yoke_"));

        // tilt about the yoke axle
        pose.translate(0.0, 0.42, -0.014);
        pose.mulPose(Axis.XP.rotationDegrees(-pt[1]));
        pose.translate(0.0, -0.42, 0.014);
        mesh.render(pose, vc, light, g -> g.startsWith("head_") || g.startsWith("lens")
                || g.startsWith("tilt_"));
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
