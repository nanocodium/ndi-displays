package dev.nano.ndidisplays.activecam;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class ActiveCamLook {

    private ActiveCamLook() {
    }

    /** Minecraft yaw/pitch that aims the eye at {@code target}. */
    public static void lookAt(ActiveCamPose pose, Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        pose.pan = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
        pose.tilt = (float) (Mth.atan2(-d.y, horiz) * Mth.RAD_TO_DEG);
        pose.tilt = Mth.clamp(pose.tilt, -89.0F, 89.0F);
        pose.roll = 0.0F;
    }

    public static void slew(ActiveCamPose pose, float yawRate, float pitchRate, float zoomRate,
                            float dt) {
        addDegrees(pose, yawRate * dt, pitchRate * dt, zoomRate * dt);
    }

    /** Instant look / zoom step in degrees (one packet, applied once). */
    public static void addDegrees(ActiveCamPose pose, float dPan, float dTilt, float dFov) {
        pose.pan = Mth.wrapDegrees(pose.pan + dPan);
        pose.tilt = Mth.clamp(pose.tilt + dTilt, -89.0F, 89.0F);
        pose.fov = Mth.clamp(pose.fov + dFov, 15.0F, 100.0F);
    }
}
