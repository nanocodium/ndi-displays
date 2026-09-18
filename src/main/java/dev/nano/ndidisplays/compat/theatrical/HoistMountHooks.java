package dev.nano.ndidisplays.compat.theatrical;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Moves an Extra Lights ghost fixture off the block grid through its own mount transform.
 *
 * Extra Lights (1.4+) applies a per-fixture "mount transform" - fractional offset plus
 * yaw/pitch/roll - at the root of every one of its render paths: the body, the flat beam
 * quads, the lens, and the shared raymarched volume, which builds its world-space origin
 * from {@code getBlockPos()} plus the transformed local pose. Nothing else reaches that
 * volume: it is one renderer for every fixture in view, so a pose translation or a camera
 * shift around a single ghost either misses it or drags every other fixture's beam along.
 *
 * So for these fixtures the ghost is drawn at its whole-block cell, exactly where its
 * block position says it is, and the sub-block remainder of the truss's travel goes into
 * the mount offset. Every path then agrees on where the fixture is.
 *
 * All access is reflective: Extra Lights is not a compile dependency, and an older build
 * without the transform simply reports nothing mountable.
 */
final class HoistMountHooks {

    private static final String MOUNTABLE =
            "com.github.dumann089.theatricalextralights.blockentities.ExtraLightsLightBlockEntity";
    private static final String[] GETTERS = {
            "getMountOffsetX", "getMountOffsetY", "getMountOffsetZ",
            "getMountYaw", "getMountPitch", "getMountRoll",
    };

    private static Class<?> mountable;
    private static Method[] getters;
    private static Method setter;
    private static boolean resolved;
    private static boolean available;

    /** What the fixture's own transform was before this class touched it. */
    private static final WeakHashMap<BlockEntity, float[]> BASE = new WeakHashMap<>();
    /** What this class last wrote, to tell a reload (which resets the fields) from its own writes. */
    private static final WeakHashMap<BlockEntity, float[]> WRITTEN = new WeakHashMap<>();

    private HoistMountHooks() {
    }

    private static boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> cls = Class.forName(MOUNTABLE);
            Method[] g = new Method[GETTERS.length];
            for (int i = 0; i < GETTERS.length; i++) {
                g[i] = cls.getMethod(GETTERS[i]);
            }
            Method s = cls.getMethod("setMountTransform",
                    float.class, float.class, float.class, float.class, float.class, float.class);
            mountable = cls;
            getters = g;
            setter = s;
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            available = false;
        }
        return available;
    }

    static boolean isMountable(@Nullable BlockEntity be) {
        return be != null && resolve() && mountable.isInstance(be);
    }

    /**
     * Sets the ghost's mount offset to its own offset plus {@code delta}, the fixture's
     * displacement from its block cell this frame.
     *
     * The fixture's own transform is re-read whenever the fields no longer hold what this
     * class last wrote, which is exactly when a live update has reloaded the ghost from NBT.
     */
    static void mountAt(BlockEntity be, Vec3 delta) {
        if (!isMountable(be)) {
            return;
        }
        try {
            float[] current = read(be);
            float[] written = WRITTEN.get(be);
            float[] base = BASE.get(be);
            if (base == null || written == null || !Arrays.equals(current, written)) {
                base = current;
                BASE.put(be, base);
            }
            setter.invoke(be,
                    base[0] + (float) delta.x, base[1] + (float) delta.y, base[2] + (float) delta.z,
                    base[3], base[4], base[5]);
            // Read back: the setter clamps, and the comparison above must see what stuck.
            WRITTEN.put(be, read(be));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Cosmetic: the fixture draws on the grid this frame.
        }
    }

    private static float[] read(BlockEntity be) throws ReflectiveOperationException {
        float[] out = new float[GETTERS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) getters[i].invoke(be)).floatValue();
        }
        return out;
    }
}
