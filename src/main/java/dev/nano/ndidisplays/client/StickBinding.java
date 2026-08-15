package dev.nano.ndidisplays.client;

import net.minecraft.network.chat.Component;

/**
 * Which raw joystick axes drive one stick, plus per-axis invert from calibration.
 * Stored as {@code x,y} with an optional leading minus, e.g. {@code 3,-4}.
 * Legacy values {@code left} / {@code right} still resolve to the GLFW gamepad pair.
 */
public record StickBinding(int xAxis, int yAxis, boolean invertX, boolean invertY) {

    public static StickBinding left() {
        return new StickBinding(0, 1, false, false);
    }

    public static StickBinding right() {
        return new StickBinding(2, 3, false, false);
    }

    public static StickBinding parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("left")) {
            return left();
        }
        if (raw.equalsIgnoreCase("right")) {
            return right();
        }
        String flags = "";
        String body = raw;
        int colon = raw.indexOf(':');
        if (colon >= 0) {
            flags = raw.substring(colon + 1).toLowerCase();
            body = raw.substring(0, colon);
        }
        String[] parts = body.split(",");
        if (parts.length < 2) {
            return left();
        }
        boolean invertX = parts[0].trim().startsWith("-") || parts[0].trim().startsWith("~")
                || flags.contains("x");
        boolean invertY = parts[1].trim().startsWith("-") || parts[1].trim().startsWith("~")
                || flags.contains("y");
        return new StickBinding(
                Math.abs(parseAxis(parts[0])),
                Math.abs(parseAxis(parts[1])),
                invertX,
                invertY);
    }

    private static int parseAxis(String token) {
        String t = token.trim();
        if (t.startsWith("-") || t.startsWith("~")) {
            t = t.substring(1);
        }
        if (t.regionMatches(true, 0, "axis:", 0, 5)) {
            t = t.substring(5);
        }
        try {
            return Math.max(0, Integer.parseInt(t));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * D-input pads often park LT on axis 2 at -1, with the right stick on 3/4.
     * Only used for the legacy {@code right} token so an uncalibrated Xbox pad still looks.
     */
    public StickBinding resolveRight(float[] raw) {
        if (xAxis != 2 || yAxis != 3 || raw == null || raw.length < 5) {
            return this;
        }
        if (raw[2] < -0.75F || raw[2] > 0.75F) {
            return new StickBinding(3, 4, invertX, invertY);
        }
        return this;
    }

    public String serialize() {
        String flags = (invertX ? "x" : "") + (invertY ? "y" : "");
        return xAxis + "," + yAxis + (flags.isEmpty() ? "" : ":" + flags);
    }

    public Component label() {
        return Component.translatable("gui.ndidisplays.pad.stick_axes", xAxis, yAxis);
    }

    public float readX(float[] axes) {
        return read(axes, xAxis, invertX);
    }

    public float readY(float[] axes) {
        return read(axes, yAxis, invertY);
    }

    private static float read(float[] axes, int index, boolean invert) {
        if (axes == null || index < 0 || index >= axes.length) {
            return 0.0F;
        }
        float value = DroneGamepad.deadzone(axes[index]);
        return invert ? -value : value;
    }
}
