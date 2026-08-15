package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.gui.DroneConfigScreen;
import dev.nano.ndidisplays.entity.DroneEntity;
import dev.nano.ndidisplays.path.DronePath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** World markers for the open drone-path GUI — never drawn otherwise. */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DronePathMarkers {

    private DronePathMarkers() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        DroneEntity drone = DroneConfigScreen.markersDrone();
        if (drone == null || !drone.isAlive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        VertexConsumer lines = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        DronePath path = drone.path();
        Vec3 prev = null;
        int i = 0;
        for (DronePath.Waypoint waypoint : path.points()) {
            Vec3 p = waypoint.pos;
            float r = i == 0 ? 0.2F : 0.85F;
            float g = 0.85F;
            float b = i == path.size() - 1 ? 0.2F : 0.35F;
            AABB box = new AABB(p.x - 0.12, p.y - 0.12, p.z - 0.12,
                    p.x + 0.12, p.y + 0.12, p.z + 0.12);
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1.0F);
            if (prev != null) {
                LevelRenderer.renderLineBox(pose, lines,
                        new AABB(prev, p).inflate(0.01), 0.4F, 0.7F, 1.0F, 0.7F);
            }
            prev = p;
            i++;
        }
        pose.popPose();
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
    }
}
