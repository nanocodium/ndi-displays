package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.ClientConfig;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import javax.annotation.Nullable;

/** Xbox-style pad for the Active Cam desk. Bindings live in {@code active_cam_pad}. */
public final class ActiveCamGamepad {

    public static final float DEADZONE = 0.16F;

    private static final GLFWGamepadState PAD = GLFWGamepadState.create();
    private static boolean recordHeld;
    private static boolean estopHeld;
    private static boolean menuHeld;
    private static boolean playHeld;
    private static boolean stopHeld;

    public record State(float moveX, float moveZ, float lookYaw, float lookPitch, float zoom,
                        boolean record, boolean estop, boolean menu, boolean play, boolean stop) {
    }

    private ActiveCamGamepad() {
    }

    @Nullable
    public static State poll() {
        StickBinding move = StickBinding.parse(ClientConfig.ACTIVE_CAM_PAD_MOVE_STICK.get());
        StickBinding look = StickBinding.parse(ClientConfig.ACTIVE_CAM_PAD_LOOK_STICK.get());
        boolean invert = ClientConfig.ACTIVE_CAM_PAD_INVERT_LOOK_Y.get();
        State best = null;
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (!GLFW.glfwJoystickPresent(jid) || !GLFW.glfwJoystickIsGamepad(jid)) {
                continue;
            }
            if (!GLFW.glfwGetGamepadState(jid, PAD)) {
                continue;
            }
            float mx = dead(PAD.axes(move.xAxis()));
            float mz = dead(PAD.axes(move.yAxis()));
            float lx = dead(PAD.axes(look.xAxis()));
            float ly = dead(PAD.axes(look.yAxis()));
            if (move.invertX()) {
                mx = -mx;
            }
            if (move.invertY()) {
                mz = -mz;
            }
            if (look.invertX()) {
                lx = -lx;
            }
            if (look.invertY()) {
                ly = -ly;
            }
            if (invert) {
                ly = -ly;
            }
            float zoomIn = analog(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_ZOOM_IN.get()));
            float zoomOut = analog(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_ZOOM_OUT.get()));
            best = new State(mx, mz, lx, ly, zoomIn - zoomOut,
                    false, false, false, false, false);
        }
        if (best == null) {
            recordHeld = estopHeld = menuHeld = playHeld = stopHeld = false;
            return null;
        }
        boolean rec = pressed(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_RECORD.get()));
        boolean stop = pressed(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_ESTOP.get()));
        boolean menu = pressed(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_MENU.get()));
        boolean play = pressed(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_PLAY.get()));
        boolean pathStop = pressed(PadBinding.parse(ClientConfig.ACTIVE_CAM_PAD_STOP.get()));
        return new State(best.moveX, best.moveZ, best.lookYaw, best.lookPitch, best.zoom,
                rising(rec, 0), rising(stop, 1), rising(menu, 2), rising(play, 3), rising(pathStop, 4));
    }

    private static float dead(float v) {
        return Math.abs(v) < DEADZONE ? 0.0F : v;
    }

    private static boolean pressed(PadBinding bind) {
        if (bind.isUnbound()) {
            return false;
        }
        for (PadBinding.Part part : bind.parts()) {
            if (part.kind() == PadBinding.Kind.BUTTON && part.index() < 15
                    && PAD.buttons(part.index()) == GLFW.GLFW_PRESS) {
                return true;
            }
            if (part.kind() == PadBinding.Kind.AXIS && analogPart(part) > 0.55F) {
                return true;
            }
        }
        return false;
    }

    private static float analog(PadBinding bind) {
        float max = 0.0F;
        for (PadBinding.Part part : bind.parts()) {
            max = Math.max(max, analogPart(part));
        }
        return max;
    }

    private static float analogPart(PadBinding.Part part) {
        if (part.kind() == PadBinding.Kind.AXIS && part.index() < 6) {
            return Mth.clamp((PAD.axes(part.index()) + 1.0F) * 0.5F, 0.0F, 1.0F);
        }
        if (part.kind() == PadBinding.Kind.BUTTON && part.index() < 15
                && PAD.buttons(part.index()) == GLFW.GLFW_PRESS) {
            return 1.0F;
        }
        return 0.0F;
    }

    private static boolean rising(boolean down, int slot) {
        boolean was = switch (slot) {
            case 0 -> recordHeld;
            case 1 -> estopHeld;
            case 2 -> menuHeld;
            case 3 -> playHeld;
            default -> stopHeld;
        };
        switch (slot) {
            case 0 -> recordHeld = down;
            case 1 -> estopHeld = down;
            case 2 -> menuHeld = down;
            case 3 -> playHeld = down;
            default -> stopHeld = down;
        }
        return down && !was;
    }
}
