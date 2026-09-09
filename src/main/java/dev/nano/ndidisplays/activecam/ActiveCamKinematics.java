package dev.nano.ndidisplays.activecam;

import net.minecraft.world.phys.Vec3;

/**
 * Trapezoidal accel / cruise / decel on all three axes. Working height is a
 * setpoint that this integrates toward — never a per-tick snap.
 */
public final class ActiveCamKinematics {

    public static final float DT = 0.05F;

    private ActiveCamKinematics() {
    }

    public static void stepToward(ActiveCamPose pose, Vec3 target, float maxSpeed, float accel) {
        float a = Math.max(0.05F, accel);
        float v = Math.max(0.05F, maxSpeed);
        double[] x = axis(pose.x, pose.vx, target.x, v, a);
        double[] y = axis(pose.y, pose.vy, target.y, v, a);
        double[] z = axis(pose.z, pose.vz, target.z, v, a);
        pose.x = x[0];
        pose.vx = x[1];
        pose.y = y[0];
        pose.vy = y[1];
        pose.z = z[0];
        pose.vz = z[1];
    }

    /**
     * Stick-driven XZ: ease velocity toward the stick, then integrate.
     * Instant assign used to snap the gondola and fight the cable clamp.
     */
    public static void stepVelocityXZ(ActiveCamPose pose, double desiredVx, double desiredVz,
                                      float maxSpeed, float accel) {
        float a = Math.max(0.05F, accel);
        pose.vx = approachVel(pose.vx, desiredVx, a);
        pose.vz = approachVel(pose.vz, desiredVz, a);
        double speed = Math.hypot(pose.vx, pose.vz);
        if (speed > maxSpeed && speed > 1.0e-6) {
            double s = maxSpeed / speed;
            pose.vx *= s;
            pose.vz *= s;
        }
        pose.x += pose.vx * DT;
        pose.z += pose.vz * DT;
    }

    /** Ease only Y toward {@code targetY} (working-height setpoint). */
    public static void stepY(ActiveCamPose pose, double targetY, float maxSpeed, float accel) {
        float a = Math.max(0.05F, accel);
        float v = Math.max(0.05F, maxSpeed);
        double[] y = axis(pose.y, pose.vy, targetY, v, a);
        pose.y = y[0];
        pose.vy = y[1];
    }

    /** Decelerate XZ to a stop (stick released). */
    public static void dampXZ(ActiveCamPose pose, float accel) {
        float a = Math.max(0.05F, accel);
        pose.vx = approachVel(pose.vx, 0.0, a);
        pose.vz = approachVel(pose.vz, 0.0, a);
        pose.x += pose.vx * DT;
        pose.z += pose.vz * DT;
    }

    private static double approachVel(double current, double desired, float accel) {
        if (current < desired) {
            return Math.min(current + accel * DT, desired);
        }
        if (current > desired) {
            return Math.max(current - accel * DT, desired);
        }
        return desired;
    }

    private static double[] axis(double current, double velocity, double target,
                                 float maxV, float accel) {
        double dist = target - current;
        if (Math.abs(dist) < 1.0e-4 && Math.abs(velocity) < 1.0e-3) {
            return new double[]{target, 0.0};
        }
        double dir = Math.signum(dist);
        double stopDist = (velocity * velocity) / (2.0 * accel);
        double desired = (Math.signum(velocity) == dir && stopDist >= Math.abs(dist))
                ? 0.0 : dir * maxV;
        velocity = approachVel(velocity, desired, accel);
        double step = velocity * DT;
        if (Math.signum(step) == dir && Math.abs(step) >= Math.abs(dist)) {
            return new double[]{target, 0.0};
        }
        return new double[]{current + step, velocity};
    }
}
