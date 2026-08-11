package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The route a dolly rides: an ordered run of connected track blocks, smoothed into a curve
 * and measured by arc length so a dolly can be placed at any distance along it.
 *
 * Track connects horizontally in any direction, so a run may bend. Corners are rounded with
 * a Catmull-Rom spline through the block centres rather than turned squarely — a dolly
 * snapping 90 degrees at a corner would look nothing like a real curved rail. A run whose
 * ends meet is a closed loop, and a dolly on a loop travels continuously around instead of
 * ping-ponging.
 */
public final class TrackPath {

    /** Bound on a single run, so a pathological rail network cannot stall the client. */
    public static final int MAX_TRACK_BLOCKS = 256;
    /** Spline samples per track block; enough that linear interpolation reads as a curve. */
    private static final int SAMPLES_PER_SEGMENT = 6;

    private final List<Vec3> points;
    private final double[] distances;
    private final double length;
    private final boolean loop;

    private TrackPath(List<Vec3> points, boolean loop) {
        this.points = points;
        this.loop = loop;
        this.distances = new double[points.size()];
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i).distanceTo(points.get(i - 1));
            distances[i] = total;
        }
        this.length = total;
    }

    public boolean isLoop() {
        return loop;
    }

    /** Total travel distance in blocks; zero when there is no usable run. */
    public double length() {
        return length;
    }

    public boolean isUsable() {
        return length > 0.5;
    }

    /** Bounding box covering the whole run, for render culling. */
    public net.minecraft.world.phys.AABB bounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Vec3 p : points) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        return new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Position at {@code travelled} blocks along the run. A loop wraps; an open run
     * ping-pongs back and forth like a real dolly reaching the end of its rail.
     */
    public Vec3 positionAt(double travelled) {
        return sample(normalise(travelled));
    }

    /**
     * Heading in degrees at {@code travelled}, so the dolly and its camera face along the
     * rail through curves. Uses Minecraft's yaw convention.
     */
    public float yawAt(double travelled) {
        double d = normalise(travelled);
        double ahead = Math.min(d + 0.25, length);
        double behind = Math.max(d - 0.25, 0.0);
        Vec3 delta = sample(ahead).subtract(sample(behind));
        if (delta.horizontalDistanceSqr() < 1.0e-6) {
            return 0.0F;
        }
        // Matches Direction.toYRot()/Vec3.directionFromRotation: yaw 0 faces +Z.
        return (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
    }

    /** Maps raw travel onto a distance along the polyline, wrapping or bouncing. */
    private double normalise(double travelled) {
        if (length <= 0.0) {
            return 0.0;
        }
        if (loop) {
            double d = travelled % length;
            return d < 0 ? d + length : d;
        }
        double cycle = travelled % (length * 2.0);
        if (cycle < 0) {
            cycle += length * 2.0;
        }
        return cycle <= length ? cycle : length * 2.0 - cycle;
    }

    private Vec3 sample(double distance) {
        if (points.isEmpty()) {
            return Vec3.ZERO;
        }
        if (points.size() == 1 || distance <= 0.0) {
            return points.get(0);
        }
        if (distance >= length) {
            return points.get(points.size() - 1);
        }
        int low = 1;
        int high = points.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (distances[mid] < distance) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        double span = distances[low] - distances[low - 1];
        double t = span <= 1.0e-9 ? 0.0 : (distance - distances[low - 1]) / span;
        return points.get(low - 1).lerp(points.get(low), t);
    }

    /**
     * Builds the run containing {@code start}, or null when that block is not on usable
     * track. Never loads chunks: an unloaded neighbour simply ends the run.
     */
    public static TrackPath build(BlockGetter level, BlockPos start) {
        if (!isTrack(level, start)) {
            return null;
        }
        List<BlockPos> ordered = order(level, start);
        if (ordered.size() < 2) {
            return null;
        }
        boolean loop = ordered.size() > 2 && areNeighbours(ordered.get(0), ordered.get(ordered.size() - 1));
        List<Vec3> centres = new ArrayList<>(ordered.size());
        for (BlockPos pos : ordered) {
            // Rail surface sits at the top of the block.
            centres.add(new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
        }
        return new TrackPath(smooth(centres, loop), loop);
    }

    /**
     * Walks the connected run into a single ordered sequence. Starts from an end when the
     * run has one, so an open rail is ordered end-to-end; a closed loop has no end and can
     * start anywhere.
     */
    private static List<BlockPos> order(BlockGetter level, BlockPos start) {
        BlockPos head = findEnd(level, start);
        List<BlockPos> ordered = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos cursor = head;
        while (cursor != null && ordered.size() < MAX_TRACK_BLOCKS && visited.add(cursor.immutable())) {
            ordered.add(cursor.immutable());
            BlockPos next = null;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = cursor.relative(dir);
                if (isTrack(level, candidate) && !visited.contains(candidate.immutable())) {
                    next = candidate;
                    break;
                }
            }
            cursor = next;
        }
        return ordered;
    }

    /** Walks to one end of the run; returns {@code start} unchanged for a closed loop. */
    private static BlockPos findEnd(BlockGetter level, BlockPos start) {
        BlockPos previous = null;
        BlockPos cursor = start;
        for (int guard = 0; guard < MAX_TRACK_BLOCKS; guard++) {
            BlockPos next = null;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = cursor.relative(dir);
                if (isTrack(level, candidate) && !candidate.equals(previous)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null || next.equals(start)) {
                return cursor; // dead end, or we came all the way round a loop
            }
            previous = cursor;
            cursor = next;
        }
        return cursor;
    }

    /**
     * Catmull-Rom through the block centres. This is what turns a staircase of block
     * positions into a rail a dolly can glide along, and what makes a ring of track read as
     * a circle rather than a polygon.
     */
    private static List<Vec3> smooth(List<Vec3> centres, boolean loop) {
        int n = centres.size();
        if (n < 3) {
            return centres;
        }
        List<Vec3> out = new ArrayList<>(n * SAMPLES_PER_SEGMENT + 1);
        int lastSegment = loop ? n : n - 1;
        for (int i = 0; i < lastSegment; i++) {
            Vec3 p0 = centres.get(wrap(i - 1, n, loop));
            Vec3 p1 = centres.get(wrap(i, n, loop));
            Vec3 p2 = centres.get(wrap(i + 1, n, loop));
            Vec3 p3 = centres.get(wrap(i + 2, n, loop));
            for (int s = 0; s < SAMPLES_PER_SEGMENT; s++) {
                out.add(catmullRom(p0, p1, p2, p3, (double) s / SAMPLES_PER_SEGMENT));
            }
        }
        // Close the ring, or finish an open run exactly on its last block.
        out.add(loop ? out.get(0) : centres.get(n - 1));
        return out;
    }

    /** Clamps at the ends of an open run; wraps around a loop. */
    private static int wrap(int index, int n, boolean loop) {
        if (loop) {
            return ((index % n) + n) % n;
        }
        return Math.max(0, Math.min(n - 1, index));
    }

    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double a = -0.5 * t3 + t2 - 0.5 * t;
        double b = 1.5 * t3 - 2.5 * t2 + 1.0;
        double c = -1.5 * t3 + 2.0 * t2 + 0.5 * t;
        double d = 0.5 * t3 - 0.5 * t2;
        return new Vec3(
                p0.x * a + p1.x * b + p2.x * c + p3.x * d,
                p0.y * a + p1.y * b + p2.y * c + p3.y * d,
                p0.z * a + p1.z * b + p2.z * c + p3.z * d);
    }

    private static boolean areNeighbours(BlockPos a, BlockPos b) {
        return a.distManhattan(b) == 1;
    }

    private static boolean isTrack(BlockGetter level, BlockPos pos) {
        if (level instanceof LevelReader reader && !reader.hasChunkAt(pos)) {
            return false;
        }
        return level.getBlockState(pos).getBlock() instanceof CameraTrackBlock;
    }
}
