package dev.nano.ndidisplays.block;

import net.minecraft.util.Mth;

/**
 * Range clamping that also rejects non-finite values.
 *
 * {@link Mth#clamp(float, float, float)} passes NaN straight through
 * ({@code NaN < min} is false, and {@code Math.min(NaN, max)} is NaN), so a
 * hand-crafted packet or a corrupt save could put NaN into a shader uniform or
 * into the camera view math. Config values arrive from clients and from NBT on
 * disk, so both paths are sanitised here.
 */
public final class Clamps {

    private Clamps() {
    }

    /** Clamps to [min, max], substituting {@code fallback} for NaN. */
    public static float f(float value, float min, float max, float fallback) {
        if (Float.isNaN(value)) {
            return fallback;
        }
        return Mth.clamp(value, min, max);
    }

    /** Clamps to [min, max]; integers cannot be non-finite. */
    public static int i(int value, int min, int max) {
        return Mth.clamp(value, min, max);
    }

    /** Trims a client-supplied name to a safe length. */
    public static String name(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
