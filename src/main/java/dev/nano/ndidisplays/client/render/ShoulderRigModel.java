package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
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
 * The geometry is the shoulder_rig OBJ mesh drawn through {@link ObjPartMesh}; the armour
 * texture camera_rig_layer_1.png is its material palette strip.
 */
public class ShoulderRigModel extends HumanoidModel<LivingEntity> {

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

        // The body part carries no cubes of its own: it only supplies the pose (body yaw, sneak
        // lean, riding) that renderToBuffer hangs the OBJ rig off.
        CubeListBuilder rig = CubeListBuilder.create();
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

    /** Mesh scale: the source rig is life-size (0.63 m long); Minecraft kit reads chunkier. */
    private static final float SCALE = 1.2F;

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer vc, int light, int overlay,
                               float r, float g, float b, float a) {
        // Follow the body part's pose, then place the mesh over the right shoulder. Mesh space
        // is y-up with the lens at +Z; player model space is y-down with forward at -Z, so a
        // half turn about X maps one onto the other (a proper rotation — no winding flip).
        pose.pushPose();
        this.body.translateAndRotate(pose);
        pose.translate(-0.28F, 0.02F, -0.06F);
        pose.mulPose(Axis.XP.rotationDegrees(180.0F));
        pose.scale(SCALE, SCALE, SCALE);
        ObjPartMesh mesh = ObjPartMesh.get("shoulder_rig");
        mesh.render(pose, vc, light, n -> !n.equals("battery_led") && !n.equals("monitor_dot"));
        mesh.render(pose, vc, LightTexture.FULL_BRIGHT,
                n -> n.equals("battery_led") || n.equals("monitor_dot"));
        pose.popPose();
    }
}
