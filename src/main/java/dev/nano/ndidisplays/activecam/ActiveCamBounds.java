package dev.nano.ndidisplays.activecam;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Authorised volume for the gondola: the box of the four winch anchors, inset,
 * with min/max Y and a max cable length. Never lets the camera leave.
 */
public final class ActiveCamBounds {

    public static final double INSET = 0.75;
    public static final double DEFAULT_MAX_CABLE = 64.0;
    public static final double MIN_HANG = 0.6;

    public final double minX;
    public final double maxX;
    public final double minY;
    public final double maxY;
    public final double minZ;
    public final double maxZ;
    public final double maxCable;
    public final Vec3[] drums;

    private ActiveCamBounds(double minX, double maxX, double minY, double maxY,
                            double minZ, double maxZ, double maxCable, Vec3[] drums) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.maxCable = maxCable;
        this.drums = drums;
    }

    public static ActiveCamBounds of(List<Vec3> drumPositions, double maxCable) {
        if (drumPositions.size() != 4) {
            throw new IllegalArgumentException("Active Cam needs four winch drums");
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        Vec3[] drums = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            Vec3 d = drumPositions.get(i);
            drums[i] = d;
            minX = Math.min(minX, d.x);
            maxX = Math.max(maxX, d.x);
            minY = Math.min(minY, d.y);
            maxY = Math.max(maxY, d.y);
            minZ = Math.min(minZ, d.z);
            maxZ = Math.max(maxZ, d.z);
        }
        double ix = Math.min(INSET, (maxX - minX) * 0.45);
        double iz = Math.min(INSET, (maxZ - minZ) * 0.45);
        double floor = minY - maxCable;
        double ceiling = minY - MIN_HANG;
        if (ceiling < floor) {
            ceiling = floor;
        }
        double x0 = minX + ix;
        double x1 = maxX - ix;
        double z0 = minZ + iz;
        double z1 = maxZ - iz;
        // Cable limit must cover the whole working box. Otherwise the four spheres
        // only meet at one point and the camera crawls millimetres then snaps back.
        double need = 0.0;
        for (Vec3 drum : drums) {
            for (double x : new double[]{x0, x1}) {
                for (double y : new double[]{floor, ceiling}) {
                    for (double z : new double[]{z0, z1}) {
                        need = Math.max(need, drum.distanceTo(new Vec3(x, y, z)));
                    }
                }
            }
        }
        return new ActiveCamBounds(x0, x1, floor, ceiling, z0, z1,
                Math.max(maxCable, need + 0.25), drums);
    }

    public static ActiveCamBounds ofBlocks(List<BlockPos> winches, double maxCable) {
        return of(winches.stream()
                .map(ActiveCamBounds::drumOf)
                .toList(), maxCable);
    }

    public static Vec3 drumOf(BlockPos winch) {
        return new Vec3(winch.getX() + 0.5, winch.getY() + 0.275, winch.getZ() + 0.5);
    }

    public Vec3 center(double workingHeight) {
        return new Vec3((minX + maxX) * 0.5, Mth.clamp(workingHeight, minY, maxY),
                (minZ + maxZ) * 0.5);
    }

    public AABB aabb() {
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public Vec3 clamp(Vec3 pos) {
        double x = Mth.clamp(pos.x, minX, maxX);
        double y = Mth.clamp(pos.y, minY, maxY);
        double z = Mth.clamp(pos.z, minZ, maxZ);
        Vec3 clamped = new Vec3(x, y, z);
        return clampCables(clamped);
    }

    public double clampHeight(double y) {
        return Mth.clamp(y, minY, maxY);
    }

    public static double cableLength(Vec3 drum, Vec3 cam) {
        return drum.distanceTo(cam);
    }

    private Vec3 clampCables(Vec3 cam) {
        Vec3 out = cam;
        for (Vec3 drum : drums) {
            double len = cableLength(drum, out);
            if (len > maxCable && len > 1.0e-4) {
                double scale = maxCable / len;
                out = drum.add(out.subtract(drum).scale(scale));
            }
        }
        return new Vec3(
                Mth.clamp(out.x, minX, maxX),
                Mth.clamp(out.y, minY, maxY),
                Mth.clamp(out.z, minZ, maxZ));
    }
}
