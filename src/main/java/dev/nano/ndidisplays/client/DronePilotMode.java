package dev.nano.ndidisplays.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.entity.DroneEntity;
import dev.nano.ndidisplays.net.DroneActionPacket;
import dev.nano.ndidisplays.net.DroneInputPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * FPV while riding a drone. Keyboard, mouse, and Xbox / PlayStation pads
 * all drive the same stick packet. Shift / B / View leaves the drone;
 * descend is Ctrl or the left trigger.
 */
public final class DronePilotMode {

    private DronePilotMode() {
    }

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() instanceof DroneEntity;
    }

    public static DroneEntity ridden() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getVehicle() instanceof DroneEntity drone) {
            return drone;
        }
        return null;
    }

    @Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT)
    public static final class Keys {

        public static final KeyMapping ADD_WAYPOINT = new KeyMapping(
                "key.ndidisplays.drone_waypoint",
                InputConstants.Type.KEYSYM, InputConstants.KEY_B,
                "key.categories.ndidisplays");

        /** Descend — sneak is reserved for leaving the drone. */
        public static final KeyMapping DESCEND = new KeyMapping(
                "key.ndidisplays.drone_descend",
                InputConstants.Type.KEYSYM, InputConstants.KEY_LCONTROL,
                "key.categories.ndidisplays");

        private Keys() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(ADD_WAYPOINT);
            event.register(DESCEND);
        }
    }

    @Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static final class Handlers {

        private Handlers() {
        }

        /**
         * Same path boats use: write analog sticks into the rider's vanilla input
         * so the server sees {@code xxa}/{@code zza} even if the custom packet is late.
         */
        @SubscribeEvent
        public static void onMovementInput(MovementInputUpdateEvent event) {
            if (!(event.getEntity().getVehicle() instanceof DroneEntity)) {
                return;
            }
            DroneGamepad.State pad = DroneGamepad.poll();
            if (pad == null) {
                return;
            }
            var input = event.getInput();
            input.forwardImpulse = mergeAxis(input.forwardImpulse, pad.forward());
            input.leftImpulse = mergeAxis(input.leftImpulse, pad.strafe());
            if (pad.up() > 0.4F) {
                input.jumping = true;
            }
        }

        private static long lastLookNanos;

        /** Apply look every frame so the right stick is not limited to 20 Hz or overwritten. */
        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || !(player.getVehicle() instanceof DroneEntity)) {
                lastLookNanos = 0L;
                return;
            }
            DroneGamepad.State pad = DroneGamepad.poll();
            if (pad == null) {
                return;
            }
            long now = System.nanoTime();
            float dt = lastLookNanos == 0L ? 0.016F : Mth.clamp((now - lastLookNanos) / 1_000_000_000.0F, 0.001F, 0.05F);
            lastLookNanos = now;
            float yaw = pad.lookYaw() * DroneGamepad.LOOK_DEG_PER_SEC * dt;
            float pitch = pad.lookPitch() * DroneGamepad.LOOK_DEG_PER_SEC * dt;
            if (yaw == 0.0F && pitch == 0.0F) {
                return;
            }
            player.setYRot(player.getYRot() + yaw);
            player.setXRot(Mth.clamp(player.getXRot() + pitch, DroneEntity.MIN_PITCH, DroneEntity.MAX_PITCH));
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || !(player.getVehicle() instanceof DroneEntity drone)) {
                return;
            }
            mc.options.setCameraType(CameraType.FIRST_PERSON);

            // Analog impulses pick up Controllable / Controlify; booleans cover vanilla keys.
            float forward = player.input.forwardImpulse;
            float strafe = player.input.leftImpulse;
            // keyJump: riding can swallow input.jumping on some mappings / controller mods.
            long window = mc.getWindow().getWindow();
            boolean space = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)
                    == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            float vertical = (player.input.jumping || mc.options.keyJump.isDown() || space) ? 1.0F : 0.0F;
            if (Keys.DESCEND.isDown()) {
                vertical -= 1.0F;
            }

            DroneGamepad.State pad = DroneGamepad.poll();
            if (pad != null) {
                forward = mergeAxis(forward, pad.forward());
                strafe = mergeAxis(strafe, pad.strafe());
                vertical = Mth.clamp(vertical + pad.up() - pad.down(), -1.0F, 1.0F);
                if (pad.exit()) {
                    NetworkHandler.CHANNEL.sendToServer(new DroneActionPacket(
                            drone.getUUID(), DroneActionPacket.Action.EXIT, 0));
                    return;
                }
                if (pad.menu()) {
                    ClientHooks.openDroneConfig(drone.getUUID());
                    return;
                }
                if (pad.addWaypoint()) {
                    NetworkHandler.CHANNEL.sendToServer(new DroneActionPacket(
                            drone.getUUID(), DroneActionPacket.Action.ADD_HERE, 0));
                }
                if (pad.pathPlay()) {
                    NetworkHandler.CHANNEL.sendToServer(new DroneActionPacket(
                            drone.getUUID(), DroneActionPacket.Action.PLAY, 0));
                }
                if (pad.pathStop()) {
                    NetworkHandler.CHANNEL.sendToServer(new DroneActionPacket(
                            drone.getUUID(), DroneActionPacket.Action.STOP, 0));
                }
            }

            float sendForward = Mth.clamp(forward, -1.0F, 1.0F);
            float sendStrafe = Mth.clamp(strafe, -1.0F, 1.0F);
            float sendVertical = Mth.clamp(vertical, -1.0F, 1.0F);
            drone.setGimbal(player.getYRot(), player.getXRot());
            drone.applyPilotInput(sendForward, sendStrafe, sendVertical,
                    player.getYRot(), player.getXRot());
            NetworkHandler.CHANNEL.sendToServer(new DroneInputPacket(
                    drone.getUUID(), sendForward, sendStrafe, sendVertical,
                    player.getYRot(), player.getXRot()));

            while (Keys.ADD_WAYPOINT.consumeClick()) {
                NetworkHandler.CHANNEL.sendToServer(new DroneActionPacket(
                        drone.getUUID(), DroneActionPacket.Action.ADD_HERE, 0));
                player.displayClientMessage(Component.translatable(
                        "gui.ndidisplays.drone.waypoint_added", drone.path().size() + 1), true);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
            DroneEntity drone = ridden();
            if (drone == null) {
                return;
            }
            var view = drone.viewState((float) event.getPartialTick());
            moveCamera(event.getCamera(), view.pos());
            // Pilot look is the local player's rot (updated this tick by the pad).
            // The synced gimbal lags a packet behind and made the look stick feel dead.
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                event.setYaw(player.getYRot());
                event.setPitch(player.getXRot());
            } else {
                event.setYaw(view.yaw());
                event.setPitch(view.pitch());
            }
            event.setRoll(0.0F);
        }

        @SubscribeEvent
        public static void onFov(ViewportEvent.ComputeFov event) {
            DroneEntity drone = ridden();
            if (drone != null) {
                event.setFOV(drone.getFov());
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
            if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type() || !active()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            DroneEntity drone = ridden();
            if (drone == null) {
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
            if (drone.isLive()) {
                g.drawString(mc.font, "● REC  " + drone.getEffectiveSourceName(),
                        mx + 2, my + 4, 0xFFE04A3A, true);
            }
            g.drawString(mc.font, String.format("%.0f°", drone.getFov()),
                    w - mx - 28, my + 4, 0xFFFFFFFF, true);
            DroneGamepad.State pad = DroneGamepad.lastState();
            if (pad != null) {
                g.drawString(mc.font, String.format("LS %+0.2f %+0.2f   RS %+0.2f %+0.2f",
                                pad.forward(), pad.strafe(), pad.lookYaw(), pad.lookPitch()),
                        mx + 2, my + 16, 0xA0FFFFFF, true);
            }
            String hint = drone.path().isPlaying()
                    ? Component.translatable("gui.ndidisplays.drone.path_playing").getString()
                    : Component.translatable("gui.ndidisplays.drone.controls").getString();
            g.drawString(mc.font, hint, mx + 2, h - my - 12, 0xC0C0C0C0, true);
        }
    }

    private static float mergeAxis(float keyboard, float pad) {
        return Math.abs(pad) > Math.abs(keyboard) ? pad : keyboard;
    }

    /**
     * {@link Camera#setPosition} is protected. Official mappings in dev, intermediary
     * at runtime — try both so FPV sits on the lens after the rider was un-buried.
     */
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
