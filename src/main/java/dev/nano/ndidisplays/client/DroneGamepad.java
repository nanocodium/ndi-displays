package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.ClientConfig;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Xbox / PlayStation / generic pads for FPV. Bindings come from client options.
 * Sticks are read from raw GLFW joystick axes so D-input / 8BitDo layouts work
 * after the calibration wizard records the real indices.
 */
public final class DroneGamepad {

    public static final float DEADZONE = 0.16F;
    public static final float LOOK_DEG_PER_TICK = 8.5F;
    /** Full-stick look speed, degrees per second. */
    public static final float LOOK_DEG_PER_SEC = 220.0F;

    private static final GLFWGamepadState PAD = GLFWGamepadState.create();
    private static boolean exitHeld;
    private static boolean addHeld;
    private static boolean menuHeld;
    private static boolean playHeld;
    private static boolean stopHeld;
    @Nullable
    private static State lastState;
    /** Axes that have sat near 0 are sticks. Triggers rest at -1 and never get marked. */
    private static final boolean[] CENTERED_MAPPED = new boolean[16];
    private static final boolean[] CENTERED_RAW = new boolean[16];

    public record State(float forward, float strafe, float up, float down,
                        float lookYaw, float lookPitch, boolean exit, boolean menu,
                        boolean addWaypoint, boolean pathPlay, boolean pathStop) {
    }

    private DroneGamepad() {
    }

    public static State poll() {
        StickBinding move = StickBinding.parse(ClientConfig.DRONE_PAD_MOVE_STICK.get());
        StickBinding look = StickBinding.parse(ClientConfig.DRONE_PAD_LOOK_STICK.get());
        boolean invertLook = ClientConfig.DRONE_PAD_INVERT_LOOK_Y.get();

        State best = null;
        float bestEnergy = -1.0F;
        Snapshot bestSnap = null;
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (!GLFW.glfwJoystickPresent(jid)) {
                continue;
            }
            Snapshot snap = GLFW.glfwJoystickIsGamepad(jid) ? fromGamepad(jid) : fromRaw(jid);
            if (snap == null) {
                continue;
            }
            State state = strongestRead(snap, move, look, invertLook);
            float energy = Math.abs(state.forward) + Math.abs(state.strafe)
                    + Math.abs(state.lookYaw) + Math.abs(state.lookPitch)
                    + state.up + state.down;
            if (best == null || energy > bestEnergy) {
                best = state;
                bestEnergy = energy;
                bestSnap = snap;
            }
        }
        if (best == null || bestSnap == null) {
            exitHeld = addHeld = menuHeld = playHeld = stopHeld = false;
            lastState = null;
            return null;
        }
        lastState = new State(best.forward, best.strafe, best.up, best.down,
                best.lookYaw, best.lookPitch,
                rising(pressed(PadBinding.parse(ClientConfig.DRONE_PAD_EXIT.get()), bestSnap), 0),
                rising(pressed(PadBinding.parse(ClientConfig.DRONE_PAD_MENU.get()), bestSnap), 1),
                rising(pressed(PadBinding.parse(ClientConfig.DRONE_PAD_WAYPOINT.get()), bestSnap), 2),
                rising(pressed(PadBinding.parse(ClientConfig.DRONE_PAD_PATH_PLAY.get()), bestSnap), 3),
                rising(pressed(PadBinding.parse(ClientConfig.DRONE_PAD_PATH_STOP.get()), bestSnap), 4));
        return lastState;
    }

    /**
     * Move and look are chosen independently. A raw layout where the left
     * stick is 0/1 and the right stick is 4/5 used to win on move energy and
     * hide the look stick completely.
     */
    private static State strongestRead(Snapshot snap, StickBinding move, StickBinding look,
                                       boolean invertLook) {
        noteCenters(snap.axes, CENTERED_MAPPED);
        noteCenters(snap.rawAxes, CENTERED_RAW);
        float[] moveXY = bestMove(snap, move);
        float[] lookXY = bestLook(snap, look);
        float ry = invertLook ? -lookXY[1] : lookXY[1];
        return new State(-moveXY[1], -moveXY[0],
                analog(PadBinding.parse(ClientConfig.DRONE_PAD_CLIMB.get()), snap),
                analog(PadBinding.parse(ClientConfig.DRONE_PAD_DESCEND.get()), snap),
                lookXY[0], ry, false, false, false, false, false);
    }

    private static float[] bestMove(Snapshot snap, StickBinding calibrated) {
        return strongestPair(
                pair(snap.rawAxes, calibrated.xAxis(), calibrated.yAxis(), calibrated.invertX(), calibrated.invertY()),
                pair(snap.axes, calibrated.xAxis(), calibrated.yAxis(), calibrated.invertX(), calibrated.invertY()),
                pair(snap.axes, 0, 1, false, false),
                pair(snap.rawAxes, 0, 1, false, false));
    }

    /** GLFW gamepad RS is 2/3; D-input Xbox is often 3/4 or 4/5. */
    private static final int[][] LOOK_PAIRS = {{2, 3}, {3, 4}, {4, 5}, {2, 4}};

    private static float[] bestLook(Snapshot snap, StickBinding calibrated) {
        float[] best = lookPair(snap.rawAxes, CENTERED_RAW, calibrated.xAxis(), calibrated.yAxis(),
                calibrated.invertX(), calibrated.invertY());
        best = strongerPair(best, lookPair(snap.axes, CENTERED_MAPPED, calibrated.xAxis(),
                calibrated.yAxis(), calibrated.invertX(), calibrated.invertY()));
        for (int[] p : LOOK_PAIRS) {
            best = strongerPair(best, lookPair(snap.axes, CENTERED_MAPPED, p[0], p[1], false, false));
            best = strongerPair(best, lookPair(snap.rawAxes, CENTERED_RAW, p[0], p[1], false, false));
        }
        return best;
    }

    private static void noteCenters(float[] axes, boolean[] centered) {
        if (axes == null) {
            return;
        }
        int n = Math.min(axes.length, centered.length);
        for (int i = 0; i < n; i++) {
            if (Math.abs(axes[i]) < 0.30F) {
                centered[i] = true;
            }
        }
    }

    private static float[] pair(float[] axes, int x, int y, boolean invX, boolean invY) {
        return lookPair(axes, null, x, y, invX, invY);
    }

    /**
     * Sticks rest at 0. Triggers rest at -1. A pair that includes an axis which
     * has never centered is a trigger and must not drive look — that was pinning
     * the FPV camera to the sky.
     */
    private static float[] lookPair(float[] axes, @Nullable boolean[] centered,
                                    int x, int y, boolean invX, boolean invY) {
        if (axes == null || x < 0 || y < 0 || x >= axes.length || y >= axes.length) {
            return new float[]{0.0F, 0.0F};
        }
        if (centered != null) {
            if (x >= centered.length || y >= centered.length) {
                return new float[]{0.0F, 0.0F};
            }
            if (!centered[x] || !centered[y]) {
                return new float[]{0.0F, 0.0F};
            }
        } else if (axes[x] < -0.85F || axes[y] < -0.85F) {
            return new float[]{0.0F, 0.0F};
        }
        float lx = deadzone(axes[x]);
        float ly = deadzone(axes[y]);
        return new float[]{invX ? -lx : lx, invY ? -ly : ly};
    }

    private static float[] strongerPair(float[] a, float[] b) {
        float ea = Math.abs(a[0]) + Math.abs(a[1]);
        float eb = Math.abs(b[0]) + Math.abs(b[1]);
        return eb > ea ? b : a;
    }

    @SafeVarargs
    private static float[] strongestPair(float[]... pairs) {
        float[] best = pairs[0];
        for (int i = 1; i < pairs.length; i++) {
            best = strongerPair(best, pairs[i]);
        }
        return best;
    }

    @Nullable
    public static State lastState() {
        return lastState;
    }

    /** Remember the pad used during calibration so flight does not pick a ghost device. */
    public static void rememberActivePad() {
        Snapshot snap = snapshot();
        if (snap == null) {
            return;
        }
        String guid = GLFW.glfwGetJoystickGUID(snap.jid);
        ClientConfig.DRONE_PAD_GUID.set(guid == null ? "" : guid);
        ClientConfig.SPEC.save();
    }

    /**
     * First button or trigger currently held — used by the bind-capture UI.
     * Sticks are ignored so looking around does not steal a binding.
     */
    public static PadBinding.Part capture() {
        Snapshot snap = snapshot();
        if (snap == null) {
            return null;
        }
        for (int i = 0; i < snap.buttons.length; i++) {
            if (snap.buttons[i]) {
                return new PadBinding.Part(PadBinding.Kind.BUTTON, i);
            }
        }
        for (int i = 4; i < snap.axes.length; i++) {
            if (trigger(snap.axes[i]) > 0.55F) {
                return new PadBinding.Part(PadBinding.Kind.AXIS, i);
            }
        }
        return null;
    }

    /** Full hardware axes for the calibration meters. */
    @Nullable
    public static float[] rawAxes() {
        Snapshot snap = snapshot();
        if (snap == null) {
            return null;
        }
        float[] src = snap.rawAxes.length >= 2 ? snap.rawAxes : snap.axes;
        return src.clone();
    }

    @Nullable
    public static String activePadName() {
        Snapshot snap = snapshot();
        if (snap == null) {
            return null;
        }
        String name = GLFW.glfwGetJoystickName(snap.jid);
        return name == null || name.isBlank() ? null : name;
    }

    public static boolean anyPadPresent() {
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid)) {
                return true;
            }
        }
        return false;
    }

    public static float deadzone(float value) {
        float abs = Math.abs(value);
        if (abs < DEADZONE) {
            return 0.0F;
        }
        float scaled = (abs - DEADZONE) / (1.0F - DEADZONE);
        return Math.copySign(Mth.clamp(scaled, 0.0F, 1.0F), value);
    }

    private static boolean rising(boolean down, int slot) {
        boolean was = switch (slot) {
            case 0 -> exitHeld;
            case 1 -> menuHeld;
            case 2 -> addHeld;
            case 3 -> playHeld;
            default -> stopHeld;
        };
        switch (slot) {
            case 0 -> exitHeld = down;
            case 1 -> menuHeld = down;
            case 2 -> addHeld = down;
            case 3 -> playHeld = down;
            default -> stopHeld = down;
        }
        return down && !was;
    }

    private static boolean pressed(PadBinding binding, Snapshot snap) {
        return analog(binding, snap) > 0.45F;
    }

    private static float analog(PadBinding binding, Snapshot snap) {
        float max = 0.0F;
        for (PadBinding.Part part : binding.parts()) {
            if (part.kind() == PadBinding.Kind.BUTTON
                    && part.index() < snap.buttons.length && snap.buttons[part.index()]) {
                max = 1.0F;
            } else if (part.kind() == PadBinding.Kind.AXIS && part.index() < snap.axes.length) {
                max = Math.max(max, trigger(snap.axes[part.index()]));
            }
        }
        return max;
    }

    private static Snapshot snapshot() {
        Snapshot best = null;
        float bestEnergy = -1.0F;
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (!GLFW.glfwJoystickPresent(jid)) {
                continue;
            }
            Snapshot snap = GLFW.glfwJoystickIsGamepad(jid) ? fromGamepad(jid) : fromRaw(jid);
            if (snap == null) {
                continue;
            }
            float energy = energy(snap);
            if (best == null || energy > bestEnergy) {
                best = snap;
                bestEnergy = energy;
            }
        }
        return best;
    }

    private static float energy(Snapshot snap) {
        float energy = 0.0F;
        for (float axis : snap.rawAxes) {
            energy += Math.abs(deadzone(axis));
        }
        for (float axis : snap.axes) {
            energy += Math.abs(deadzone(axis));
        }
        for (boolean button : snap.buttons) {
            if (button) {
                energy += 0.15F;
            }
        }
        return energy;
    }

    private static Snapshot fromGamepad(int jid) {
        if (!GLFW.glfwGetGamepadState(jid, PAD)) {
            return fromRaw(jid);
        }
        boolean[] buttons = new boolean[15];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = PAD.buttons(i) == GLFW.GLFW_PRESS;
        }
        float[] axes = new float[6];
        for (int i = 0; i < axes.length; i++) {
            axes[i] = PAD.axes(i);
        }
        if (buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP]) {
            axes[1] = -1.0F;
        }
        if (buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN]) {
            axes[1] = 1.0F;
        }
        if (buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT]) {
            axes[0] = -1.0F;
        }
        if (buttons[GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT]) {
            axes[0] = 1.0F;
        }
        float[] raw = readRawAxes(jid);
        if (raw.length < 2) {
            raw = axes.clone();
        }
        return new Snapshot(jid, buttons, axes, raw);
    }

    private static Snapshot fromRaw(int jid) {
        float[] raw = readRawAxes(jid);
        if (raw.length < 2) {
            return null;
        }
        ByteBuffer buttonsBuf = GLFW.glfwGetJoystickButtons(jid);
        boolean[] buttons = new boolean[Math.max(15, buttonsBuf == null ? 0 : buttonsBuf.limit())];
        if (buttonsBuf != null) {
            for (int i = 0; i < buttonsBuf.limit(); i++) {
                buttons[i] = buttonsBuf.get(i) == GLFW.GLFW_PRESS;
            }
        }
        float[] axes = new float[Math.max(6, raw.length)];
        System.arraycopy(raw, 0, axes, 0, raw.length);
        ByteBuffer hats = GLFW.glfwGetJoystickHats(jid);
        if (hats != null && hats.limit() > 0) {
            byte hat = hats.get(0);
            if ((hat & GLFW.GLFW_HAT_UP) != 0) {
                axes[1] = -1.0F;
            }
            if ((hat & GLFW.GLFW_HAT_DOWN) != 0) {
                axes[1] = 1.0F;
            }
            if ((hat & GLFW.GLFW_HAT_LEFT) != 0) {
                axes[0] = -1.0F;
            }
            if ((hat & GLFW.GLFW_HAT_RIGHT) != 0) {
                axes[0] = 1.0F;
            }
        }
        return new Snapshot(jid, buttons, axes, raw);
    }

    private static float[] readRawAxes(int jid) {
        FloatBuffer axesBuf = GLFW.glfwGetJoystickAxes(jid);
        if (axesBuf == null || axesBuf.limit() < 1) {
            return new float[0];
        }
        float[] axes = new float[axesBuf.limit()];
        for (int i = 0; i < axesBuf.limit(); i++) {
            axes[i] = axesBuf.get(i);
        }
        return axes;
    }

    private static float trigger(float axis) {
        if (axis < 0.0F) {
            return Mth.clamp((axis + 1.0F) * 0.5F, 0.0F, 1.0F);
        }
        return axis > 0.12F ? Mth.clamp(axis, 0.0F, 1.0F) : 0.0F;
    }

    private record Snapshot(int jid, boolean[] buttons, float[] axes, float[] rawAxes) {
    }
}
