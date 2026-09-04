package dev.nano.ndidisplays.hoist;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Where the load is, relative to where it was picked up.
 *
 * A rig is held by one or more motors, and each motor decides for itself how much chain it
 * has out. That is what a real hoist does, and it is also what lets a truss tilt: two
 * motors at different heights hold a sloped structure between them. So the transform is
 * not a single number — it is the flat plane that best fits every motor's hook height,
 * expressed as a vertical travel plus a slope.
 *
 * <h3>Slope, not two rotations</h3>
 * The slope is stored as a gradient, {@code dy/dx} and {@code dy/dz}, because that is
 * exactly what the motors constrain and it composes without any question of rotation
 * order. The rotation used for rendering and collision is derived from it: a single turn
 * about one horizontal axis, the axis at right angles to the direction of steepest climb.
 * A truss on four motors therefore looks like a truss, not like something with independent
 * pitch and roll applied in whichever order the code happened to pick.
 *
 * <h3>Landing is still square</h3>
 * Blocks only exist on the integer grid and cannot be stored rotated, so a tilted load
 * stays an entity. {@link #flat()} is what the hoist asks before putting anything down.
 */
public record RigTransform(double travelY, double gradX, double gradZ,
                           double pivotX, double pivotY, double pivotZ) {

    public static final RigTransform IDENTITY = new RigTransform(0, 0, 0, 0, 0, 0);

    /**
     * Slope below which the load counts as level.
     *
     * A hair of slope is normal: four motors integrating their own motion profiles will
     * not agree to the last thousandth of a metre, and refusing to land over that would
     * mean a rig that can never be put down. About 2.3 degrees — enough to absorb a
     * four-point hang that has drifted, not enough to hide a deliberate rake.
     */
    private static final double FLAT_GRADIENT = 0.04;

    /**
     * One motor's demand: move the load {@code lift} metres vertically at the point it
     * grabs, {@code (x, z)}.
     *
     * The quantity is a <em>displacement</em> from capture, not an absolute height, which
     * is what makes a load that was never level to begin with — a stepped truss, a motor
     * bolted into the grid rather than above it — start out with zero slope instead of
     * lurching into a tilt the moment it leaves the ground.
     */
    public record Sample(double x, double lift, double z) {
    }

    /** True when the load is square enough with the world to become blocks again. */
    public boolean flat() {
        return Math.abs(gradX) <= FLAT_GRADIENT && Math.abs(gradZ) <= FLAT_GRADIENT;
    }

    /** Steepest slope anywhere on the load, in degrees. */
    public float tiltDegrees() {
        return (float) Math.toDegrees(Math.atan(Math.hypot(gradX, gradZ)));
    }

    /**
     * The rotation the slope corresponds to: one turn about the horizontal axis at right
     * angles to the fall line, so the uphill side rises and the downhill side drops.
     */
    public Quaternionf rotation() {
        double magnitude = Math.hypot(gradX, gradZ);
        if (magnitude < 1.0e-9) {
            return new Quaternionf();
        }
        float angle = (float) Math.atan(magnitude);
        return new Quaternionf().fromAxisAngleRad(
                (float) (-gradZ / magnitude), 0.0F, (float) (gradX / magnitude), angle);
    }

    /**
     * Moves a point from the snapshot's own coordinates to where it is being held.
     *
     * Input and output are both relative to the rig origin; the caller adds the origin to
     * get world coordinates. This is the one place the geometry lives, so the renderer,
     * the collision test and the chain all agree on where a block actually is.
     */
    public Vec3 apply(double localX, double localY, double localZ) {
        double dx = localX - pivotX;
        double dy = localY - pivotY;
        double dz = localZ - pivotZ;

        double magnitude = Math.hypot(gradX, gradZ);
        if (magnitude < 1.0e-9) {
            return new Vec3(localX, localY + travelY, localZ);
        }

        // Rodrigues about the unit horizontal axis n, by the angle whose tangent is the
        // gradient. Written out rather than routed through joml so the double precision
        // survives: a 32-block truss rounded to float would visibly step.
        double angle = Math.atan(magnitude);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double nx = -gradZ / magnitude;
        double nz = gradX / magnitude;

        // n is horizontal, so n.v reduces to the two horizontal terms.
        double dot = nx * dx + nz * dz;
        // n x v, with n.y == 0.
        double crossX = -nz * dy;
        double crossY = nz * dx - nx * dz;
        double crossZ = nx * dy;

        double rx = dx * cos + crossX * sin + nx * dot * (1 - cos);
        double ry = dy * cos + crossY * sin;
        double rz = dz * cos + crossZ * sin + nz * dot * (1 - cos);

        return new Vec3(pivotX + rx, pivotY + ry + travelY, pivotZ + rz);
    }

    /** Grid cell a block of the load currently occupies, from its snapshot offset. */
    public BlockPos cellOf(BlockPos origin, BlockPos offset) {
        Vec3 centre = apply(offset.getX() + 0.5, offset.getY() + 0.5, offset.getZ() + 0.5);
        return BlockPos.containing(
                origin.getX() + centre.x, origin.getY() + centre.y, origin.getZ() + centre.z);
    }

    /**
     * Where the rig origin sits in the world for a level transform.
     *
     * Blocks can only be placed on the integer grid, so a load that has travelled 3.4 m
     * renders at 3.4 m but lands at 3. The hoist rounds its final chain length to match
     * before it puts the load down, so what the operator sees is where it ends up. Only
     * meaningful when {@link #flat()}.
     */
    public BlockPos applyTo(BlockPos origin) {
        return origin.above((int) Math.round(travelY));
    }

    /**
     * Fits the plane the motors are asking for.
     *
     * One motor gives a plain vertical move. Two give a slope along the line between them
     * and nothing across it. Three or more give a genuine least-squares fit, which is what
     * keeps a four-point truss sane when the operator has nudged one corner: the structure
     * is rigid, so it takes the best plane through the four demands rather than tearing.
     *
     * @param samples each motor's travel at the point of the load it holds
     * @param pivotY  local height of the plane the hooks sit on, which the load turns about
     */
    public static RigTransform solve(List<Sample> samples, double pivotY) {
        if (samples.isEmpty()) {
            return IDENTITY;
        }

        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;
        for (Sample sample : samples) {
            sumX += sample.x();
            sumY += sample.lift();
            sumZ += sample.z();
        }
        int n = samples.size();
        double cx = sumX / n;
        double cy = sumY / n;
        double cz = sumZ / n;

        double sxx = 0;
        double sxz = 0;
        double szz = 0;
        double sxy = 0;
        double szy = 0;
        for (Sample sample : samples) {
            double dx = sample.x() - cx;
            double dz = sample.z() - cz;
            double dy = sample.lift() - cy;
            sxx += dx * dx;
            sxz += dx * dz;
            szz += dz * dz;
            sxy += dx * dy;
            szy += dz * dy;
        }

        // Ridge term. Without it a single motor, or several in a straight line, leaves the
        // normal equations singular; with it the unconstrained direction simply comes back
        // as zero slope, which is the answer a rigger would give.
        double ridge = 1.0e-6 * (sxx + szz) + 1.0e-9;
        sxx += ridge;
        szz += ridge;

        double determinant = sxx * szz - sxz * sxz;
        double gradX = 0;
        double gradZ = 0;
        if (Math.abs(determinant) > 1.0e-12) {
            gradX = (sxy * szz - szy * sxz) / determinant;
            gradZ = (szy * sxx - sxy * sxz) / determinant;
        }

        return new RigTransform(cy, gradX, gradZ, cx, pivotY, cz);
    }
}
