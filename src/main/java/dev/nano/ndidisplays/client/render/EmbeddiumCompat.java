package dev.nano.ndidisplays.client.render;

import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Stops Embeddium/Sodium from re-running its occlusion search for camera captures.
 *
 * Sodium's world renderer compares the camera position/rotation against its last-seen
 * values and re-runs a full synchronous section BFS when they differ. A capture moves
 * the camera to the rig and back every frame, so without this every captured frame
 * costs TWO full re-culls — one for the rig view, one for the player's next frame.
 * Instead the last-seen values are set to the rig camera before the capture (so the
 * capture reuses the player's existing render list, drawn from the rig's viewpoint)
 * and restored afterwards (so the player's frame doesn't see a phantom move either).
 *
 * The trade-off matches the vanilla-renderer pinning: rigs only see sections the
 * player's graph already contains. All reflection; Embeddium is a soft dependency.
 */
public final class EmbeddiumCompat {

    private static boolean probed;
    private static Method instanceNullable;
    private static Field lastCameraX;
    private static Field lastCameraY;
    private static Field lastCameraZ;
    private static Field lastCameraPitch;
    private static Field lastCameraYaw;

    private static Object renderer;
    private static double savedX;
    private static double savedY;
    private static double savedZ;
    private static double savedPitch;
    private static double savedYaw;
    private static boolean pinned;

    private EmbeddiumCompat() {
    }

    private static void probe() {
        probed = true;
        try {
            Class<?> swr = Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
            instanceNullable = swr.getMethod("instanceNullable");
            lastCameraX = swr.getDeclaredField("lastCameraX");
            lastCameraY = swr.getDeclaredField("lastCameraY");
            lastCameraZ = swr.getDeclaredField("lastCameraZ");
            lastCameraPitch = swr.getDeclaredField("lastCameraPitch");
            lastCameraYaw = swr.getDeclaredField("lastCameraYaw");
            lastCameraX.setAccessible(true);
            lastCameraY.setAccessible(true);
            lastCameraZ.setAccessible(true);
            lastCameraPitch.setAccessible(true);
            lastCameraYaw.setAccessible(true);
        } catch (Throwable t) {
            instanceNullable = null; // Embeddium absent or incompatible: no-op
        }
    }

    /** Call before a capture render; pass the rig camera's eye and angles. */
    public static void pinCamera(Vec3 eye, float pitch, float yaw) {
        if (!probed) {
            probe();
        }
        if (instanceNullable == null) {
            return;
        }
        try {
            renderer = instanceNullable.invoke(null);
            if (renderer == null) {
                return;
            }
            savedX = lastCameraX.getDouble(renderer);
            savedY = lastCameraY.getDouble(renderer);
            savedZ = lastCameraZ.getDouble(renderer);
            savedPitch = lastCameraPitch.getDouble(renderer);
            savedYaw = lastCameraYaw.getDouble(renderer);
            lastCameraX.setDouble(renderer, eye.x);
            lastCameraY.setDouble(renderer, eye.y);
            lastCameraZ.setDouble(renderer, eye.z);
            lastCameraPitch.setDouble(renderer, pitch);
            lastCameraYaw.setDouble(renderer, yaw);
            pinned = true;
        } catch (Throwable t) {
            pinned = false;
        }
    }

    /** Call after the capture render, mirroring {@link #pinCamera}. */
    public static void restoreCamera() {
        if (!pinned || renderer == null) {
            return;
        }
        pinned = false;
        try {
            lastCameraX.setDouble(renderer, savedX);
            lastCameraY.setDouble(renderer, savedY);
            lastCameraZ.setDouble(renderer, savedZ);
            lastCameraPitch.setDouble(renderer, savedPitch);
            lastCameraYaw.setDouble(renderer, savedYaw);
        } catch (Throwable ignored) {
        } finally {
            renderer = null;
        }
    }
}
