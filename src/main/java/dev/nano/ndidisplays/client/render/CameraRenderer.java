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
 * shoulder pad, viewfinder, matte box and lens rings; PTZ with LED ring and
 * yoke; jib truss boom with cabling and striped counterweights; track dolly
 * with wheel bogies. Live rigs show a red tally and a thin aim laser derived
 * from the same view state the NDI capture uses (beam == centre of the feed).
 * Static bases/tripods come from the block's JSON model.
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
        // lens control cable
        box(pose, vc, light, 0.066F, 0.0F, 0.10F, 0.078F, 0.012F, 0.30F, CABLE);
        if (be.isActive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.035F, 0.11F, -0.365F, 0.035F, 0.165F, -0.35F, TALLY);
        }
        box(pose, vc, LightTexture.FULL_BRIGHT, 0.055F, -0.03F, -0.356F, 0.075F, -0.01F, -0.35F, BLUE_LED);
        pose.popPose();
    }

    // --- PTZ dome ---------------------------------------------------------

    private void renderPtz(NdiCameraBlockEntity be, PoseStack pose, VertexConsumer vc, int light) {
        float[] pt = be.getEasedPanTilt();
        pose.pushPose();
        pose.translate(0.5, 0.34, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(be.getFacing().toYRot() + pt[0])));

        // Rotating pan platter: octagonal drum, glowing cyan status ring around its
        // waist, brushed top cap, connector field at the back.
        octoY(pose, vc, light, 0.125F, 0.0F, 0.05F, PTZ_BODY);
        octoY(pose, vc, LightTexture.FULL_BRIGHT, 0.1285F, 0.016F, 0.032F, PTZ_RING);
        octoY(pose, vc, light, 0.102F, 0.05F, 0.064F, PTZ_BODY_LIGHT);
        box(pose, vc, light, -0.05F, 0.006F, 0.118F, 0.05F, 0.046F, 0.132F, CONNECTOR);

        // Yoke arms: graphite uprights with brushed chamfered shoulders and silver
        // tilt-pivot caps on the outside (octagonal, like a real drive hub).
        box(pose, vc, light, -0.138F, 0.055F, -0.034F, -0.096F, 0.245F, 0.034F, PTZ_BODY);
        box(pose, vc, light, 0.096F, 0.055F, -0.034F, 0.138F, 0.245F, 0.034F, PTZ_BODY);
        box(pose, vc, light, -0.132F, 0.245F, -0.026F, -0.102F, 0.263F, 0.026F, PTZ_BODY_LIGHT);
        box(pose, vc, light, 0.102F, 0.245F, -0.026F, 0.132F, 0.263F, 0.026F, PTZ_BODY_LIGHT);
        octoX(pose, vc, light, -0.145F, -0.138F, 0.16F, 0.05F, PTZ_SILVER);
        octoX(pose, vc, light, 0.138F, 0.145F, 0.16F, 0.05F, PTZ_SILVER);

        // Tilting camera pod between the arms.
        pose.translate(0.0, 0.16, 0.0);
        pose.mulPose(Axis.XP.rotationDegrees(-pt[1]));
        // main pod with chamfered top and bottom so it reads rounded
        box(pose, vc, light, -0.092F, -0.068F, -0.118F, 0.092F, 0.068F, 0.092F, PTZ_BODY);
        box(pose, vc, light, -0.078F, 0.068F, -0.108F, 0.078F, 0.078F, 0.082F, PTZ_BODY_LIGHT);
        box(pose, vc, light, -0.078F, -0.078F, -0.108F, 0.078F, -0.068F, 0.082F, PTZ_BODY);
        // side vents + thin silver trim lines
        box(pose, vc, light, -0.0928F, -0.032F, -0.082F, -0.092F, 0.034F, 0.02F, PTZ_VENT);
        box(pose, vc, light, 0.092F, -0.032F, -0.082F, 0.0928F, 0.034F, 0.02F, PTZ_VENT);
        box(pose, vc, light, -0.0928F, 0.042F, -0.10F, -0.092F, 0.052F, 0.055F, PTZ_SILVER);
        box(pose, vc, light, 0.092F, 0.042F, -0.10F, 0.0928F, 0.052F, 0.055F, PTZ_SILVER);
        // rear: connector field + always-on power LED
        box(pose, vc, light, -0.05F, -0.03F, -0.1225F, 0.05F, 0.04F, -0.118F, CONNECTOR);
        box(pose, vc, LightTexture.FULL_BRIGHT, 0.056F, -0.052F, -0.121F, 0.072F, -0.038F, -0.118F, BLUE_LED);
        // lens train, octagonal: graphite collar → piano-black zoom barrel →
        // silver front bezel → recessed glass with cyan bloom
        octoZ(pose, vc, light, 0.092F, 0.112F, 0.054F, PTZ_BODY);
        octoZ(pose, vc, light, 0.112F, 0.158F, 0.047F, PTZ_GLOSS);
        octoZ(pose, vc, light, 0.158F, 0.176F, 0.051F, PTZ_SILVER);
        box(pose, vc, light, -0.038F, -0.038F, 0.1745F, 0.038F, 0.038F, 0.1765F, PTZ_GLASS);
        // IR window under the lens on the front face
        box(pose, vc, light, -0.05F, -0.063F, 0.0921F, 0.05F, -0.045F, 0.0945F, PTZ_IR);
        // tally on the pod's top front
        if (be.isActive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.03F, 0.0785F, 0.018F, 0.03F, 0.0925F, 0.068F, TALLY);
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
        box(pose, vc, light, -0.125F, -0.17F, -0.945F, 0.125F, 0.12F, -0.875F, BODY);
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

    private void renderTrack(NdiCameraBlockEntity be, float partialTick, PoseStack pose, VertexConsumer vc, int light) {
        Vec3 dolly = be.getDollyPos(partialTick).subtract(Vec3.atLowerCornerOf(be.getBlockPos()));
        // The chassis follows the rail, so on a curve or a ring the dolly leans into the
        // bend instead of sliding sideways with its wheels across the track.
        float baseYaw = be.getDollyYaw(partialTick);
        pose.pushPose();
        pose.translate(dolly.x, dolly.y, dolly.z);
        pose.mulPose(Axis.YP.rotationDegrees(-baseYaw));
        // platform: carbon deck, hazard edge trim
        box(pose, vc, light, -0.27F, 0.05F, -0.21F, 0.27F, 0.105F, 0.21F, BODY);
        box(pose, vc, light, -0.27F, 0.105F, -0.21F, 0.27F, 0.115F, 0.21F, CARBON);
        box(pose, vc, light, -0.275F, 0.07F, -0.215F, 0.275F, 0.095F, -0.205F, HAZARD);
        box(pose, vc, light, -0.275F, 0.07F, 0.205F, 0.275F, 0.095F, 0.215F, HAZARD);
        // wheel bogies: silver side plates + rubber wheels
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float cx = 0.18F * sx;
                float cz = 0.13F * sz;
                box(pose, vc, light, cx - 0.055F, 0.0F, cz - 0.052F, cx + 0.055F, 0.05F, cz - 0.042F, SILVER);
                box(pose, vc, light, cx - 0.055F, 0.0F, cz + 0.042F, cx + 0.055F, 0.05F, cz + 0.052F, SILVER);
                box(pose, vc, light, cx - 0.045F, -0.015F, cz - 0.04F, cx + 0.045F, 0.055F, cz + 0.04F, GRIP);
            }
        }
        // cable drag on the deck
        box(pose, vc, light, -0.24F, 0.115F, 0.12F, -0.10F, 0.135F, 0.19F, CABLE);
        // riser + pan/tilt remote head
        pose.translate(0.0, 0.115, 0.0);
        box(pose, vc, light, -0.05F, 0.0F, -0.05F, 0.05F, 0.055F, 0.05F, SILVER);
        pose.translate(0.0, 0.055, 0.0);
        // Only pan here: the chassis already carries the rail heading, so pan is relative to
        // the direction of travel — matching how getViewState aims the capture.
        pose.mulPose(Axis.YP.rotationDegrees(-be.getPan()));
        pose.mulPose(Axis.XP.rotationDegrees(-be.getTilt()));
        box(pose, vc, light, -0.09F, 0.0F, -0.17F, 0.09F, 0.155F, 0.10F, BODY);
        box(pose, vc, light, -0.095F, 0.02F, -0.10F, -0.089F, 0.135F, 0.02F, VENT);
        box(pose, vc, light, 0.089F, 0.02F, -0.10F, 0.095F, 0.135F, 0.02F, VENT);
        box(pose, vc, light, -0.06F, 0.02F, -0.176F, 0.06F, 0.135F, -0.17F, LCD);
        // lens: grip ring, barrel, glass
        box(pose, vc, light, -0.058F, 0.022F, 0.10F, 0.058F, 0.132F, 0.15F, GRIP);
        box(pose, vc, light, -0.048F, 0.032F, 0.15F, 0.048F, 0.122F, 0.19F, BLACK);
        box(pose, vc, light, -0.04F, 0.04F, 0.188F, 0.04F, 0.114F, 0.193F, LENS);
        if (be.isActive()) {
            box(pose, vc, LightTexture.FULL_BRIGHT, -0.02F, 0.155F, -0.10F, 0.02F, 0.168F, -0.05F, TALLY);
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
