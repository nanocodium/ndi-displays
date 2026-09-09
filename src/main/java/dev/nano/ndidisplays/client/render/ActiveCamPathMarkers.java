package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import dev.nano.ndidisplays.client.ActiveCamPilotMode;
import dev.nano.ndidisplays.client.gui.ActiveCamControllerScreen;
import dev.nano.ndidisplays.path.DronePath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** World markers for the Active Cam path while FPV or the controller GUI is open. */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ActiveCamPathMarkers {

    private ActiveCamPathMarkers() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ActiveCamControllerBlockEntity ctrl = null;
        if (mc.screen instanceof ActiveCamControllerScreen screen) {
            ctrl = screen.controller();
        } else {
            BlockPos pos = ActiveCamPilotMode.controlling();
            if (pos != null && mc.level.getBlockEntity(pos) instanceof ActiveCamControllerBlockEntity be) {
                ctrl = be;
            }
        }
        if (ctrl == null || ctrl.getLevel() != mc.level) {
            return;
        }
        DronePath path = ctrl.waypoints();
        if (path.isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        VertexConsumer lines = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Vec3 prev = null;
        int i = 0;
        for (DronePath.Waypoint waypoint : path.points()) {
            Vec3 p = waypoint.pos;
            float r = i == 0 ? 0.55F : 0.75F;
            float g = 0.35F;
            float b = i == path.size() - 1 ? 0.95F : 0.85F;
            AABB box = new AABB(p.x - 0.12, p.y - 0.12, p.z - 0.12,
                    p.x + 0.12, p.y + 0.12, p.z + 0.12);
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1.0F);
            if (prev != null) {
                LevelRenderer.renderLineBox(pose, lines,
                        new AABB(prev, p).inflate(0.01), 0.7F, 0.45F, 1.0F, 0.7F);
            }
            prev = p;
            i++;
        }
        pose.popPose();
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
    }
}
