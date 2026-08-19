package dev.nano.ndidisplays.path;

import dev.nano.ndidisplays.block.Clamps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A free-space flight path: waypoints in the world, smoothed with the same Catmull-Rom
 * plus arc-length sampling the dolly uses on rails. Modes are once, loop, and ping-pong.
 */
public final class DronePath {

    public static final int MAX_WAYPOINTS = 64;
    private static final int SAMPLES_PER_SEGMENT = 8;

    public enum Mode {
        ONCE, LOOP, PING_PONG;

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final class Waypoint {
        public Vec3 pos;
        public float gimbalYaw;
        public float gimbalPitch;
        public float speed;
        public int holdTicks;

        public Waypoint(Vec3 pos, float gimbalYaw, float gimbalPitch, float speed, int holdTicks) {
            this.pos = pos;
            this.gimbalYaw = gimbalYaw;
            this.gimbalPitch = gimbalPitch;
            this.speed = speed;
            this.holdTicks = holdTicks;
        }

        public Waypoint copy() {
            return new Waypoint(pos, gimbalYaw, gimbalPitch, speed, holdTicks);
        }
    }

    public record Sample(Vec3 pos, float yaw, float pitch) {
    }

    private final List<Waypoint> points = new ArrayList<>();
    private Mode mode = Mode.ONCE;
    private boolean playing;
    private boolean reverse;
    private double travelled;
    private int holdLeft;
    private int holdAtIndex = -1;

    private List<Vec3> samples = List.of();
    private double[] distances = new double[0];
    private double length;
    private double[] waypointDistance = new double[0];

    public List<Waypoint> points() {
        return Collections.unmodifiableList(points);
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.ONCE : mode;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    public void add(Waypoint waypoint) {
        if (points.size() >= MAX_WAYPOINTS) {
            return;
        }
        points.add(sanitize(waypoint));
        rebuild();
    }

    public void remove(int index) {
        if (index < 0 || index >= points.size()) {
            return;
        }
        points.remove(index);
        rebuild();
        if (playing && points.size() < 2) {
            stop();
        }
    }

    public void move(int index, int delta) {
        int dest = index + delta;
        if (index < 0 || index >= points.size() || dest < 0 || dest >= points.size()) {
            return;
        }
        Waypoint moved = points.remove(index);
        points.add(dest, moved);
        rebuild();
    }

    public void clear() {
        points.clear();
        stop();
        rebuild();
    }

    public void play() {
        if (points.size() < 2) {
            return;
        }
        playing = true;
        reverse = false;
        travelled = 0.0;
        holdLeft = 0;
        holdAtIndex = -1;
        rebuild();
    }

    public void stop() {
        playing = false;
        reverse = false;
        travelled = 0.0;
        holdLeft = 0;
        holdAtIndex = -1;
    }

    /**
     * Advances the path by one tick. {@code null} when nothing should move the drone
     * (stopped, holding, or not enough points).
     */
    public Sample tick() {
        if (!playing || points.size() < 2 || length <= 1.0e-4) {
            return null;
        }
        if (holdLeft > 0) {
            holdLeft--;
            return sampleAt(travelled);
        }
        float speed = speedAt(travelled);
        double step = Math.max(0.05, speed) / 20.0;
        double next = travelled + (reverse ? -step : step);

        int crossed = waypointCrossed(travelled, next);
        if (crossed >= 0 && crossed != holdAtIndex && points.get(crossed).holdTicks > 0) {
            travelled = waypointDistance[crossed];
            holdLeft = points.get(crossed).holdTicks;
            holdAtIndex = crossed;
            return sampleAt(travelled);
        }

        if (mode == Mode.LOOP) {
            travelled = wrap(next, length);
        } else if (mode == Mode.PING_PONG) {
            if (next >= length) {
                travelled = length;
                reverse = true;
            } else if (next <= 0.0) {
                travelled = 0.0;
                reverse = false;
            } else {
                travelled = next;
            }
        } else {
            if (next >= length) {
                travelled = length;
                playing = false;
            } else {
                travelled = next;
            }
        }
        return sampleAt(travelled);
    }

    public Sample sampleAt(double distance) {
        if (samples.isEmpty()) {
            if (points.isEmpty()) {
                return new Sample(Vec3.ZERO, 0.0F, 0.0F);
            }
            Waypoint only = points.get(0);
            return new Sample(only.pos, only.gimbalYaw, only.gimbalPitch);
        }
        double d = Mth.clamp(distance, 0.0, length);
        Vec3 pos = interpolatePosition(d);
        int[] pair = waypointPair(d);
        Waypoint a = points.get(pair[0]);
        Waypoint b = points.get(pair[1]);
        float t = pairBlend(d, pair[0], pair[1]);
        return new Sample(pos, rotLerp(a.gimbalYaw, b.gimbalYaw, t),
                Mth.lerp(t, a.gimbalPitch, b.gimbalPitch));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", mode.name());
        tag.putBoolean("playing", playing);
        tag.putBoolean("reverse", reverse);
        tag.putDouble("travelled", travelled);
        tag.putInt("holdLeft", holdLeft);
        ListTag list = new ListTag();
        for (Waypoint point : points) {
            CompoundTag n = new CompoundTag();
            n.putDouble("x", point.pos.x);
            n.putDouble("y", point.pos.y);
            n.putDouble("z", point.pos.z);
            n.putFloat("yaw", point.gimbalYaw);
            n.putFloat("pitch", point.gimbalPitch);
            n.putFloat("speed", point.speed);
            n.putInt("hold", point.holdTicks);
            list.add(n);
        }
        tag.put("points", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        points.clear();
        try {
            mode = Mode.valueOf(tag.getString("mode"));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.ONCE;
        }
        playing = tag.getBoolean("playing");
        reverse = tag.getBoolean("reverse");
        travelled = tag.getDouble("travelled");
        holdLeft = tag.getInt("holdLeft");
        ListTag list = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && points.size() < MAX_WAYPOINTS; i++) {
            CompoundTag n = list.getCompound(i);
            points.add(sanitize(new Waypoint(
                    new Vec3(n.getDouble("x"), n.getDouble("y"), n.getDouble("z")),
                    n.getFloat("yaw"), n.getFloat("pitch"),
                    n.getFloat("speed"), n.getInt("hold"))));
        }
        rebuild();
        if (playing && points.size() < 2) {
            stop();
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(mode.ordinal());
        buf.writeBoolean(playing);
        buf.writeVarInt(points.size());
        for (Waypoint point : points) {
            buf.writeDouble(point.pos.x);
            buf.writeDouble(point.pos.y);
            buf.writeDouble(point.pos.z);
            buf.writeFloat(point.gimbalYaw);
            buf.writeFloat(point.gimbalPitch);
            buf.writeFloat(point.speed);
            buf.writeVarInt(point.holdTicks);
        }
    }

    public void read(FriendlyByteBuf buf) {
        points.clear();
        int modeOrd = buf.readVarInt();
        mode = modeOrd >= 0 && modeOrd < Mode.values().length ? Mode.values()[modeOrd] : Mode.ONCE;
        playing = buf.readBoolean();
        int count = Math.min(MAX_WAYPOINTS, Math.max(0, buf.readVarInt()));
        for (int i = 0; i < count; i++) {
            points.add(sanitize(new Waypoint(
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt())));
        }
        reverse = false;
        travelled = 0.0;
        holdLeft = 0;
        rebuild();
    }

    public static Waypoint sanitize(Waypoint waypoint) {
        Vec3 pos = waypoint.pos == null ? Vec3.ZERO : waypoint.pos;
        return new Waypoint(pos,
                Clamps.f(waypoint.gimbalYaw, -180.0F, 180.0F, 0.0F),
                Clamps.f(waypoint.gimbalPitch, -85.0F, 30.0F, 0.0F),
                Clamps.f(waypoint.speed, 0.25F, 16.0F, 4.0F),
                Clamps.i(waypoint.holdTicks, 0, 200));
    }

    private void rebuild() {
        holdAtIndex = -1;
        if (points.size() < 2) {
            samples = points.isEmpty() ? List.of() : List.of(points.get(0).pos);
            distances = new double[samples.size()];
            length = 0.0;
            waypointDistance = new double[points.size()];
            return;
        }
        List<Vec3> centres = new ArrayList<>(points.size());
        for (Waypoint point : points) {
            centres.add(point.pos);
        }
        boolean loop = mode == Mode.LOOP && points.size() >= 3;
        samples = smooth(centres, loop);
        distances = new double[samples.size()];
        double total = 0.0;
        for (int i = 1; i < samples.size(); i++) {
            total += samples.get(i).distanceTo(samples.get(i - 1));
            distances[i] = total;
        }
        length = total;
        waypointDistance = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            waypointDistance[i] = nearestDistance(points.get(i).pos);
        }
    }

    private float speedAt(double distance) {
        int[] pair = waypointPair(distance);
        float t = pairBlend(distance, pair[0], pair[1]);
        return Mth.lerp(t, points.get(pair[0]).speed, points.get(pair[1]).speed);
    }

    private int[] waypointPair(double distance) {
        if (points.size() == 1) {
            return new int[]{0, 0};
        }
        int next = 1;
        while (next < waypointDistance.length && waypointDistance[next] < distance) {
            next++;
        }
        int b = Math.min(points.size() - 1, next);
        int a = Math.max(0, b - 1);
        return new int[]{a, b};
    }

    private float pairBlend(double distance, int a, int b) {
        if (a == b) {
            return 0.0F;
        }
        double span = waypointDistance[b] - waypointDistance[a];
        if (span <= 1.0e-6) {
            return 0.0F;
        }
        return (float) Mth.clamp((distance - waypointDistance[a]) / span, 0.0, 1.0);
    }

    private int waypointCrossed(double from, double to) {
        double lo = Math.min(from, to);
        double hi = Math.max(from, to);
        for (int i = 0; i < waypointDistance.length; i++) {
            double d = waypointDistance[i];
            if (d > lo + 1.0e-4 && d <= hi + 1.0e-4) {
                return i;
            }
        }
        return -1;
    }

    private Vec3 interpolatePosition(double distance) {
        if (samples.isEmpty()) {
            return Vec3.ZERO;
        }
        if (samples.size() == 1 || distance <= 0.0) {
            return samples.get(0);
        }
        if (distance >= length) {
            return samples.get(samples.size() - 1);
        }
        int low = 1;
        int high = samples.size() - 1;
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
        return samples.get(low - 1).lerp(samples.get(low), t);
    }

    private double nearestDistance(Vec3 pos) {
        double best = 0.0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < samples.size(); i++) {
            double d = samples.get(i).distanceToSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = distances[i];
            }
        }
        return best;
    }

    private static List<Vec3> smooth(List<Vec3> centres, boolean loop) {
        int n = centres.size();
        if (n < 3) {
            return new ArrayList<>(centres);
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
        out.add(loop ? out.get(0) : centres.get(n - 1));
        return out;
    }

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

    private static double wrap(double value, double length) {
        if (length <= 0.0) {
            return 0.0;
        }
        double d = value % length;
        return d < 0.0 ? d + length : d;
    }

    private static float rotLerp(float a, float b, float t) {
        return a + Mth.wrapDegrees(b - a) * t;
    }
}
