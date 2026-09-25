package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.net.CameraAimPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** A stationary operator looking through the mounted lens. No player teleport is needed. */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, value = Dist.CLIENT)
public final class MountedCameraScreen extends Screen {
    private final NdiCameraBlockEntity camera;
    private Entity previousCamera;
    private CameraType previousType;
    private ArmorStand eye;
    private float pan, tilt, fov;
    private double lastX, lastY;
    private boolean haveMouse, dirty;

    public MountedCameraScreen(NdiCameraBlockEntity camera) {
        super(Component.literal("Camera operator"));
        this.camera = camera;
        pan = camera.getPan(); tilt = camera.getTilt(); fov = camera.getFov();
    }

    @Override
    protected void init() {
        if (eye == null && minecraft.level != null) {
            previousCamera = minecraft.getCameraEntity();
            previousType = minecraft.options.getCameraType();
            eye = new ArmorStand(minecraft.level, 0, 0, 0);
            eye.setInvisible(true);
            updateView(0);
            minecraft.setCameraEntity(eye);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }
        haveMouse = false;
        GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (haveMouse) {
            float sensitivity = 0.25F * fov / 70F;
            pan = Mth.wrapDegrees(pan + (float)(x - lastX) * sensitivity);
            tilt = Mth.clamp(tilt - (float)(y - lastY) * sensitivity, -85, 85);
            dirty = true;
            camera.aim(pan, tilt, fov);
        }
        lastX = x; lastY = y; haveMouse = true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        fov = Mth.clamp(fov - (float)amount * 2, 10, 110);
        camera.aim(pan, tilt, fov);
        dirty = true;
        return true;
    }

    private void flush() {
        if (dirty && minecraft.getConnection() != null) {
            NetworkHandler.CHANNEL.sendToServer(new CameraAimPacket(camera.getBlockPos(), pan, tilt, fov));
            dirty = false;
        }
    }

    @Override
    public void tick() {
        if (minecraft.player == null || minecraft.level != camera.getLevel() || camera.isRemoved()
                || minecraft.player.isDeadOrDying()
                || minecraft.player.distanceToSqr(camera.getBlockPos().getCenter()) > 64 * 64) {
            onClose();
            return;
        }
        flush();
    }

    private void updateView(float partialTick) {
        if (eye == null) return;
        var view = camera.getViewState(partialTick);
        var pos = view.pos();
        eye.setPos(pos.x, pos.y - eye.getEyeHeight(), pos.z);
        eye.xOld = eye.xo = eye.getX();
        eye.yOld = eye.yo = eye.getY();
        eye.zOld = eye.zo = eye.getZ();
        eye.setYRot(view.yaw()); eye.yRotO = view.yaw();
        eye.setXRot(view.pitch()); eye.xRotO = view.pitch();
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START
                && Minecraft.getInstance().screen instanceof MountedCameraScreen screen) {
            screen.updateView(event.renderTickTime);
        }
    }

    @SubscribeEvent
    public static void lens(ViewportEvent.ComputeFov event) {
        if (!CameraFeedManager.isCapturing()
                && Minecraft.getInstance().screen instanceof MountedCameraScreen screen) {
            event.setFOV(screen.fov);
        }
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float partialTick) {
        g.drawCenteredString(font, "Mouse: pan / tilt   Scroll: zoom   Esc: leave camera",
                width / 2, height - 25, 0xFFFFFF);
        g.drawCenteredString(font, camera.getEffectiveSourceName(), width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void removed() {
        flush();
        if (eye != null) {
            minecraft.setCameraEntity(previousCamera != null ? previousCamera : minecraft.player);
            minecraft.options.setCameraType(previousType);
            eye = null;
        }
        GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

