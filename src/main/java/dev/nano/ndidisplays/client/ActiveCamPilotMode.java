package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.activecam.ActiveCamHeadMode;
import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import dev.nano.ndidisplays.entity.ActiveCamGondolaEntity;
import dev.nano.ndidisplays.net.ActiveCamActionPacket;
import dev.nano.ndidisplays.net.ActiveCamInputPacket;
import dev.nano.ndidisplays.net.ActiveCamSnapshotPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * FPV like the drone: the player camera sits on the gondola. Mouse look is the
 * vanilla player rot (no extra NDI render). B adds a waypoint; Play flies them.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ActiveCamPilotMode {

    private static final float PAD_LOOK_DEG_PER_SEC = 180.0F;
    private static final float SCROLL_FOV = 3.0F;

    @Nullable
    private static BlockPos controlling;
    private static boolean leaving;
    private static int leaveGrace;
    private static float accZoom;
    private static boolean sentIdle;
    private static float lastSentYaw = Float.NaN;
    private static float lastSentPitch = Float.NaN;
    private static long lastLookNanos;

    private ActiveCamPilotMode() {
    }

    public static void take(BlockPos pos) {
        leaving = false;
        leaveGrace = 40;
        controlling = pos.immutable();
        accZoom = 0.0F;
        sentIdle = false;
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
        lastLookNanos = 0L;
        NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(pos,
                ActiveCamActionPacket.Action.TAKE_CONTROL));
        Minecraft mc = Minecraft.getInstance();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        LocalPlayer player = mc.player;
        if (player != null) {
            ActiveCamPose pose = ActiveCamClientState.interpolated(pos, 1.0F);
            if (pose == null && mc.level != null
                    && mc.level.getBlockEntity(pos) instanceof ActiveCamControllerBlockEntity ctrl) {
                pose = ctrl.pose();
            }
            if (pose != null) {
                player.setYRot(pose.pan);
                player.setXRot(Mth.clamp(pose.tilt, -89.0F, 89.0F));
                player.yRotO = player.getYRot();
                player.xRotO = player.getXRot();
            }
            player.displayClientMessage(Component.translatable(
                    "gui.ndidisplays.activecam.controlling"), true);
        }
    }

    public static boolean shouldKeepMounted() {
        return !leaving && leaveGrace > 0;
    }

    public static void release() {
        leaving = true;
        BlockPos pos = controlling();
        if (pos != null) {
            NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(pos,
                    ActiveCamActionPacket.Action.RELEASE_CONTROL));
        }
        controlling = null;
        leaveGrace = 0;
        lastLookNanos = 0L;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            if (mc.player.getVehicle() instanceof ActiveCamGondolaEntity) {
                mc.player.stopRiding();
            }
            mc.player.displayClientMessage(Component.translatable(
                    "gui.ndidisplays.activecam.released"), true);
        }
    }

    public static boolean isControlling(BlockPos pos) {
        if (controlling != null && controlling.equals(pos)) {
            return true;
        }
        ActiveCamGondolaEntity gondola = ridden();
        return gondola != null && pos.equals(gondola.controllerPos());
    }

    @Nullable
    public static BlockPos controlling() {
        if (controlling != null) {
            return controlling;
        }
        ActiveCamGondolaEntity gondola = ridden();
        return gondola == null ? null : gondola.controllerPos();
    }

    public static boolean active() {
        return controlling() != null;
    }

    @Nullable
    public static ActiveCamGondolaEntity ridden() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() instanceof ActiveCamGondolaEntity gondola) {
            return gondola;
        }
        return null;
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (controlling == null) {
            return;
        }
        // Riding uses vanilla zza/xxa on the server — do not zero the sticks.
        if (event.getEntity().getVehicle() instanceof ActiveCamGondolaEntity) {
            return;
        }
        var input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (controlling == null) {
            return;
        }
        accZoom += (float) Math.signum(event.getScrollDelta()) * SCROLL_FOV;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || controlling == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) {
            lastLookNanos = 0L;
            return;
        }
        ActiveCamGamepad.State pad = ActiveCamGamepad.poll();
        if (pad == null) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastLookNanos == 0L
                ? 0.016F
                : Mth.clamp((now - lastLookNanos) / 1_000_000_000.0F, 0.001F, 0.05F);
        lastLookNanos = now;
        float yaw = pad.lookYaw() * PAD_LOOK_DEG_PER_SEC * dt;
        float pitch = pad.lookPitch() * PAD_LOOK_DEG_PER_SEC * dt;
        if (yaw == 0.0F && pitch == 0.0F) {
            return;
        }
        player.setYRot(player.getYRot() + yaw);
        player.setXRot(Mth.clamp(player.getXRot() + pitch, -89.0F, 89.0F));
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (leaveGrace > 0) {
            leaveGrace--;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (controlling == null) {
            ActiveCamGondolaEntity gondola = ridden();
            if (gondola != null) {
                controlling = gondola.controllerPos();
            }
        }
        if (controlling == null && ridden() == null) {
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean pressR = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
        boolean sneak = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
                || mc.options.keyShift.isDown();
        if (pressR || (sneak && leaveGrace <= 0)) {
            release();
            return;
        }

        if (controlling == null) {
            return;
        }
        if (mc.level == null) {
            return;
        }
        ActiveCamControllerBlockEntity ctrl = mc.level.getBlockEntity(controlling)
                instanceof ActiveCamControllerBlockEntity be ? be : null;
        if (mc.screen != null) {
            return;
        }

        mc.options.setCameraType(CameraType.FIRST_PERSON);

        if (ctrl == null) {
            return;
        }

        float zoom = accZoom;
        accZoom = 0.0F;

        float forward = 0.0F;
        float strafe = 0.0F;
        float vertical = 0.0F;
        ActiveCamGamepad.State pad = ActiveCamGamepad.poll();
        if (pad != null) {
            strafe += pad.moveX();
            forward += -pad.moveZ();
            zoom += pad.zoom() * 2.5F;
            if (pad.estop()) {
                NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(controlling,
                        ActiveCamActionPacket.Action.ESTOP));
            }
            if (pad.record()) {
                addWaypoint(ctrl);
            }
            if (pad.play()) {
                NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(controlling,
                        ActiveCamActionPacket.Action.PLAY));
            }
            if (pad.stop()) {
                NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(controlling,
                        ActiveCamActionPacket.Action.STOP));
            }
            if (pad.menu()) {
                ClientHooks.openActiveCamController(controlling);
            }
        }
        if (mc.options.keyUp.isDown()
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Z) == GLFW.GLFW_PRESS) {
            forward += 1.0F;
        }
        if (mc.options.keyDown.isDown()
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) {
            forward -= 1.0F;
        }
        if (mc.options.keyLeft.isDown()
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Q) == GLFW.GLFW_PRESS) {
            strafe -= 1.0F;
        }
        if (mc.options.keyRight.isDown()
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) {
            strafe += 1.0F;
        }
        boolean space = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS
                || mc.options.keyJump.isDown();
        if (space) {
            vertical += 1.0F;
        }
        if (DronePilotMode.Keys.DESCEND.isDown()
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS) {
            vertical -= 1.0F;
        }

        while (DronePilotMode.Keys.ADD_WAYPOINT.consumeClick()) {
            addWaypoint(ctrl);
        }

        float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);
        float mx = Mth.clamp(-sin * forward + cos * strafe, -1.0F, 1.0F);
        float mz = Mth.clamp(cos * forward + sin * strafe, -1.0F, 1.0F);
        float my = Mth.clamp(vertical, -1.0F, 1.0F);
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        boolean moving = Math.abs(mx) > 0.01F || Math.abs(mz) > 0.01F || Math.abs(my) > 0.01F
                || Math.abs(zoom) > 0.01F
                || Float.isNaN(lastSentYaw)
                || Math.abs(yaw - lastSentYaw) > 0.05F
                || Math.abs(pitch - lastSentPitch) > 0.05F;
        if (!moving) {
            if (sentIdle) {
                return;
            }
            sentIdle = true;
        } else {
            sentIdle = false;
        }
        lastSentYaw = yaw;
        lastSentPitch = pitch;
        NetworkHandler.CHANNEL.sendToServer(new ActiveCamInputPacket(
                controlling, mx, mz, my, yaw, pitch, zoom));
    }

    private static void addWaypoint(ActiveCamControllerBlockEntity ctrl) {
        NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(controlling,
                ActiveCamActionPacket.Action.RECORD));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(
                    "gui.ndidisplays.activecam.waypoint_added", ctrl.waypoints().size() + 1), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (CameraFeedManager.isCapturing()) {
            return;
        }
        BlockPos cam = controlling();
        if (cam == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ActiveCamPose pose = ActiveCamClientState.interpolated(cam, (float) event.getPartialTick());
        ActiveCamControllerBlockEntity ctrl = null;
        if (mc.level != null && mc.level.getBlockEntity(cam)
                instanceof ActiveCamControllerBlockEntity be) {
            ctrl = be;
            if (pose == null) {
                pose = be.pose();
            }
        }
        if (pose == null) {
            return;
        }
        if (ridden() == null) {
            moveCamera(event.getCamera(), new Vec3(pose.x, pose.y, pose.z));
        }
        ActiveCamSnapshotPacket snap = ActiveCamClientState.latest(cam);
        LocalPlayer player = mc.player;
        boolean pathOrTrack = (snap != null && snap.playing())
                || (ctrl != null && ctrl.liveHead() == ActiveCamHeadMode.TRACK_TARGET);
        if (pathOrTrack) {
            event.setYaw(pose.pan);
            event.setPitch(pose.tilt);
            if (player != null && snap != null && snap.playing()) {
                player.setYRot(pose.pan);
                player.setXRot(pose.tilt);
                player.yRotO = pose.pan;
                player.xRotO = pose.tilt;
            }
        } else if (player != null) {
            event.setYaw(player.getYRot());
            event.setPitch(player.getXRot());
        } else {
            event.setYaw(pose.pan);
            event.setPitch(pose.tilt);
        }
        event.setRoll(pose.roll);
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (CameraFeedManager.isCapturing()) {
            return;
        }
        BlockPos cam = controlling();
        if (cam == null) {
            return;
        }
        ActiveCamPose pose = ActiveCamClientState.interpolated(cam, (float) event.getPartialTick());
        if (pose != null) {
            event.setFOV(pose.fov);
        }
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (active()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type() || controlling == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        var g = event.getGuiGraphics();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int mx = Math.round(w * 0.08F);
        int my = Math.round(h * 0.08F);
        int arm = Math.max(8, Math.min(w, h) / 14);
        int c = 0xA0FFFFFF;
        g.fill(mx, my, mx + arm, my + 1, c);
        g.fill(mx, my, mx + 1, my + arm, c);
        g.fill(w - mx - arm, my, w - mx, my + 1, c);
        g.fill(w - mx - 1, my, w - mx, my + arm, c);
        g.fill(mx, h - my - 1, mx + arm, h - my, c);
        g.fill(mx, h - my - arm, mx + 1, h - my, c);
        g.fill(w - mx - arm, h - my - 1, w - mx, h - my, c);
        g.fill(w - mx - 1, h - my - arm, w - mx, h - my, c);
        g.fill(w / 2 - 5, h / 2, w / 2 + 6, h / 2 + 1, c);
        g.fill(w / 2, h / 2 - 5, w / 2 + 1, h / 2 + 6, c);

        ActiveCamSnapshotPacket snap = ActiveCamClientState.latest(controlling);
        ActiveCamPose pose = ActiveCamClientState.interpolated(controlling, 1.0F);
        if (snap != null && snap.eStop()) {
            g.drawString(mc.font, Component.translatable("gui.ndidisplays.activecam.estop_on").getString(),
                    mx + 2, my + 4, 0xFFFF5555, true);
        } else if (snap != null && snap.live()) {
            g.drawString(mc.font, "● LIVE", mx + 2, my + 4, 0xFFE04A3A, true);
        }
        if (pose != null) {
            g.drawString(mc.font, String.format("%.0f°", pose.fov),
                    w - mx - 28, my + 4, 0xFFFFFFFF, true);
        }
        int pts = 0;
        if (mc.level != null && mc.level.getBlockEntity(controlling)
                instanceof ActiveCamControllerBlockEntity ctrl) {
            pts = ctrl.waypoints().size();
        }
        boolean playing = snap != null && snap.playing();
        String hint = playing
                ? Component.translatable("gui.ndidisplays.activecam.path_playing").getString()
                : Component.translatable("gui.ndidisplays.activecam.hud", pts).getString();
        g.drawString(mc.font, hint, mx + 2, h - my - 12, 0xC0C0C0C0, true);
    }

    private static void moveCamera(Camera camera, Vec3 pos) {
        for (String name : new String[]{"setPosition", "m_90584_"}) {
            try {
                var method = Camera.class.getDeclaredMethod(name, double.class, double.class, double.class);
                method.setAccessible(true);
                method.invoke(camera, pos.x, pos.y, pos.z);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }
}
