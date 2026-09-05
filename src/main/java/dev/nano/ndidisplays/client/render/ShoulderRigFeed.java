package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.client.ShoulderOperatorMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * The shoulder rig's on-board monitor, showing the frame the rig is actually sending.
 *
 * Third person: the armour layer draws the rig mesh, whose monitor_screen face is a blank
 * panel; it leaves its model matrix here and the level pass paints the live frame over that
 * face. First person: the player model is not drawn at all, so the monitor arm and screen are
 * rendered with the hands, fixed to the view and placed where the third-person rig puts them
 * relative to the eye. Operator mode already fills the screen with the lens view, so nothing is drawn then.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class ShoulderRigFeed {

    private static final ResourceLocation ATLAS =
            new ResourceLocation(NdiDisplays.MODID, "textures/entity/shoulder_rig_atlas.png");

    // The monitor_screen face in mesh space (after import): a thin panel facing -Z, which the
    // rig transform turns toward the operator's face. The 16:9 picture fills its width.
    private static final float SX0 = 0.128F;
    private static final float SX1 = 0.214F;
    private static final float SY_MID = 0.315F;
    private static final float SZ = -0.0395F;

    /** Eye height in body-model space (y down from the neck): 1.62 against a 1.501 neck. */
    private static final float EYE_Y = -(1.62F - 1.501F);

    private static final Matrix4f WORN = new Matrix4f();
    private static boolean wornPending;

    private ShoulderRigFeed() {
    }

    /** Called by the worn model while it draws the local player's rig: remember where it is. */
    public static void noteWorn(PoseStack pose) {
        WORN.set(pose.last().pose());
        wornPending = true;
    }

    private static boolean wearing(LocalPlayer player) {
        return player != null && player.getItemBySlot(EquipmentSlot.CHEST)
                .is(NdiDisplays.SHOULDER_CAMERA_ITEM.get());
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !wornPending) {
            return;
        }
        wornPending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getCameraType().isFirstPerson() || !wearing(mc.player)) {
            return; // an inventory preview left the matrix, not the world pass
        }
        drawScreen(WORN);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || CameraFeedManager.isCapturing()
                || ShoulderOperatorMode.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!wearing(player) || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // Camera space -> body space about the eye. The monitor rides with the view rather than
        // the body: a real operator's eye stays on the finder however they turn, and a screen
        // that swung out of shot every time they looked round was useless. A half turn about Z
        // (model x and y both run the other way), then the eye offset, then the worn placement.
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        pose.translate(0.0F, -EYE_Y, 0.0F);
        ShoulderRigModel.placeRig(pose);

        ObjPartMesh mesh = ObjPartMesh.get("shoulder_rig");
        VertexConsumer vc = event.getMultiBufferSource()
                .getBuffer(RenderType.entityCutoutNoCull(ATLAS));
        int light = event.getPackedLight();
        mesh.render(pose, vc, light, n -> n.startsWith("monitor_") && !n.equals("monitor_dot"));
        mesh.render(pose, vc, LightTexture.FULL_BRIGHT, n -> n.equals("monitor_dot"));
        drawScreen(pose.last().pose());
        pose.popPose();
    }

    /** The live picture on the monitor face, under the given mesh-space model matrix. */
    private static void drawScreen(Matrix4f mat) {
        int tex = CameraFeedManager.shoulderCaptureTexture();
        int tw = CameraFeedManager.shoulderCaptureWidth();
        int th = CameraFeedManager.shoulderCaptureHeight();
        if (tex == 0 || tw <= 0 || th <= 0) {
            return;
        }
        float w = SX1 - SX0;
        float h = w * th / (float) tw;
        float y0 = SY_MID - h * 0.5F;
        float y1 = SY_MID + h * 0.5F;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        // Proud of the mesh's own screen face by a depth-buffer step, as the LED walls do.
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        RenderSystem.enablePolygonOffset();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Facing -Z in mesh space; the capture is bottom-up, so V is 0 at the bottom edge. Seen
        // from -Z, +X is on the viewer's left, so U runs 1 -> 0 along +X to keep the picture
        // unmirrored for the operator.
        b.vertex(mat, SX0, y0, SZ).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, SX1, y0, SZ).uv(0.0F, 0.0F).endVertex();
        b.vertex(mat, SX1, y1, SZ).uv(0.0F, 1.0F).endVertex();
        b.vertex(mat, SX0, y1, SZ).uv(1.0F, 1.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.enableCull();
    }
}
