package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.LivingEntity;

/**
 * A shoulder-mounted cine camera rig, worn in the chest slot — a large-format body on a
 * baseplate over the right shoulder, PL zoom and matte box out front, top handle with
 * accessories, a V-mount brick counterweighting it at the back, and the operator's handgrip
 * under the lens. Modelled on an ARRI Alexa 265 built out for ENG-style shoulder work.
 *
 * Built as a {@link HumanoidModel} so the armour layer drives it: the rig then follows the
 * body through sneaking, riding and swimming for free, and shows on other players without any
 * extra syncing. Only {@code body} carries cubes; every other humanoid part stays empty, and
 * the layer hides the ones it would otherwise draw.
 *
 * The texture is flat colour zones rather than a painted skin — each cube's UV net sits wholly
 * inside one zone, so geometry can be reshaped without repainting anything.
 */
public class ShoulderRigModel extends HumanoidModel<LivingEntity> {

    // Texture zone origins, matching the generated camera_rig_layer_1.png.
    private static final int DARK = 0;      // (0,0)   body, lens, matte box
    private static final int GREY_U = 64;   // (64,0)  handle, rods, shoulder pad
    private static final int BATT_V = 64;   // (0,64)  battery brick
    private static final int ORANGE_U = 64; // (64,64) battery accent
    private static final int RED_U = 96;    // (96,64) REC lamp
    private static final int YELLOW_V = 96; // (0,96)  lens scale ring
    private static final int SCREEN_U = 64; // (64,96) monitor face

    public ShoulderRigModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Empty humanoid parts: the armour layer expects them to exist, and leaving them
        // cube-less is what keeps the player's own body visible.
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.ZERO);

        // Everything hangs off the body. Player space: -x is the player's right, -z is
        // forward, y grows downward from the neck. The rig sits over the right shoulder with
        // the lens pointing forward, which is why every x below is negative.
        CubeListBuilder rig = CubeListBuilder.create()

                // --- shoulder pad and baseplate (the bit actually resting on them) ---
                .texOffs(GREY_U, 0)
                .addBox(-7.5F, -1.0F, -2.5F, 7.0F, 2.0F, 7.0F)      // pad over the trapezius
                .texOffs(GREY_U, 12)
                .addBox(-7.0F, -3.0F, -6.0F, 6.0F, 1.5F, 11.0F)     // dovetail baseplate

                // --- camera body: the big block, sat on the plate ---
                .texOffs(DARK, 0)
                .addBox(-7.0F, -9.5F, -5.0F, 6.0F, 6.5F, 8.0F)
                // side monitor panel (FPS / SHUTTER / EI readout)
                .texOffs(SCREEN_U, 96)
                .addBox(-7.6F, -8.5F, -3.5F, 0.7F, 3.5F, 5.0F)
                // REC lamp
                .texOffs(RED_U, BATT_V)
                .addBox(-7.4F, -4.5F, -4.0F, 0.6F, 1.0F, 1.0F)

                // --- lens: barrel forward of the body, scale ring, matte box ---
                .texOffs(DARK, 20)
                .addBox(-6.0F, -8.5F, -10.0F, 4.0F, 4.0F, 5.0F)     // PL barrel
                .texOffs(DARK, YELLOW_V)
                .addBox(-6.2F, -8.7F, -11.5F, 4.4F, 4.4F, 1.5F)     // focus scale ring
                .texOffs(DARK, 30)
                .addBox(-6.8F, -9.6F, -14.0F, 5.6F, 6.2F, 2.5F)     // matte box
                .texOffs(GREY_U, 24)
                .addBox(-7.4F, -11.2F, -14.0F, 6.8F, 1.6F, 0.8F)    // top french flag

                // --- top handle on cheese-plate risers ---
                .texOffs(GREY_U, 30)
                .addBox(-6.4F, -11.0F, -9.0F, 0.9F, 1.5F, 0.9F)     // front riser
                .texOffs(GREY_U, 30)
                .addBox(-6.4F, -11.0F, 1.0F, 0.9F, 1.5F, 0.9F)      // rear riser
                .texOffs(GREY_U, 34)
                .addBox(-6.6F, -12.3F, -9.4F, 1.4F, 1.3F, 11.6F)    // handle bar

                // --- top accessories: wireless video tx and its two antennas ---
                .texOffs(DARK, 40)
                .addBox(-5.0F, -12.0F, -1.0F, 3.0F, 1.8F, 3.0F)     // tx body
                .texOffs(GREY_U, 40)
                .addBox(-4.4F, -15.5F, -0.4F, 0.5F, 3.5F, 0.5F)     // antenna 1
                .texOffs(GREY_U, 40)
                .addBox(-3.2F, -15.5F, -0.4F, 0.5F, 3.5F, 0.5F)     // antenna 2

                // --- V-mount battery hanging off the back, balancing the lens ---
                .texOffs(DARK, BATT_V)
                .addBox(-6.6F, -9.0F, 3.0F, 5.2F, 5.6F, 3.2F)
                .texOffs(ORANGE_U, BATT_V)
                .addBox(-6.8F, -8.0F, 3.4F, 0.4F, 1.6F, 2.4F)       // orange accent strip

                // --- 15mm rods and the operator's handgrip under the lens ---
                .texOffs(GREY_U, 46)
                .addBox(-6.2F, -3.2F, -12.0F, 0.8F, 0.8F, 7.0F)     // rod, outboard
                .texOffs(GREY_U, 46)
                .addBox(-3.2F, -3.2F, -12.0F, 0.8F, 0.8F, 7.0F)     // rod, inboard
                .texOffs(GREY_U, 50)
                .addBox(-5.6F, -2.6F, -9.5F, 2.6F, 1.4F, 2.6F)      // rosette block
                .texOffs(DARK, 50)
                .addBox(-5.2F, -1.4F, -9.2F, 1.8F, 4.6F, 1.8F);     // handgrip

        root.addOrReplaceChild("body", rig, PartPose.ZERO);
        return LayerDefinition.create(mesh, 128, 128);
    }

    /**
     * The rig is rigid kit bolted to a plate, so it must not inherit the arm swing the
     * humanoid model would otherwise copy onto it. Body rotation is kept — that is what makes
     * it turn with the operator — and the limbs are zeroed.
     */
    @Override
    public void setupAnim(LivingEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        this.rightArm.xRot = 0.0F;
        this.rightArm.yRot = 0.0F;
        this.rightArm.zRot = 0.0F;
        this.leftArm.xRot = 0.0F;
        this.leftArm.yRot = 0.0F;
        this.leftArm.zRot = 0.0F;
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer vc, int light, int overlay,
                               float r, float g, float b, float a) {
        // Only the body carries geometry; drawing it alone avoids the empty parts costing
        // anything and keeps the player's own model untouched.
        this.body.render(pose, vc, light, overlay, r, g, b, a);
    }
}
