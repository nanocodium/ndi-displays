package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Shared block entity for all camera rigs. Stores the NDI/output config
 * (server-authoritative, synced to clients) and computes the camera's view
 * position/rotation over time, including the jib sweep and track dolly motion.
 */
public class NdiCameraBlockEntity extends BlockEntity {

    /** Immutable view state: where the camera eye is and where it points. */
    public record ViewState(Vec3 pos, float yaw, float pitch) {
    }

    public static final int[] RES_W = {960, 1280, 1920};
    public static final int[] RES_H = {540, 720, 1080};

    /** Matches the wire limit in {@code UpdateCameraConfigPacket} and the GUI edit box. */
    public static final int MAX_SOURCE_NAME = 128;

    private static final int DEFAULT_RESOLUTION = 1; // index into RES_W/RES_H
    private static final int DEFAULT_FPS = 30;
    private static final float DEFAULT_FOV = 60.0F;
    private static final float DEFAULT_PTZ_SPEED = 45.0F;   // deg/s slew
    private static final float DEFAULT_JIB_ARM = 5.0F;      // metres
    /**
     * Longest crane arm, metres. The arm is drawn as a few boxes and the tip is pure
     * trigonometry, so length costs nothing to render — the old 8m ceiling was short of
     * even a modest studio jib, let alone the big telescopic cranes this is imitating.
     */
    public static final float MAX_JIB_ARM = 24.0F;

    /**
     * Where the motion-control rig's head sits relative to its dolly, before the rig slews.
     *
     * These mirror the arm built in {@code CameraRenderer.renderTrack}: the boom lifts the arm
     * and the arm reaches forward, so the camera is well above and ahead of the deck rather
     * than perched on it. The eye has to come from the same place the head is drawn, or the
     * feed does not match the machine.
     */
    public static final double MILO_HEAD_UP = 1.29;
    public static final double MILO_HEAD_FORWARD = 0.75;
    /** Elevation limits when an operator is flying the arm, degrees from level. */
    private static final float JIB_MIN_ELEV = -35.0F;
    private static final float JIB_MAX_ELEV = 80.0F;
    private static final float DEFAULT_JIB_SWEEP = 70.0F;   // total sweep, degrees
    private static final float DEFAULT_JIB_PERIOD = 14.0F;  // seconds per full oscillation
    private static final float DEFAULT_TRACK_SPEED = 1.0F;  // m/s

    private String sourceName = "";
    private boolean active = true;
    private int resolution = DEFAULT_RESOLUTION;
    private int fps = DEFAULT_FPS;
    private float fov = DEFAULT_FOV;
    private float pan = 0.0F;   // degrees, + = viewer right
    private float tilt = 0.0F;  // degrees, + = up
    // kind-specific
    private float ptzSpeed = DEFAULT_PTZ_SPEED;
    private float jibArmLength = DEFAULT_JIB_ARM;
    private float jibSweep = DEFAULT_JIB_SWEEP;
    private float jibPeriod = DEFAULT_JIB_PERIOD;
    private float trackSpeed = DEFAULT_TRACK_SPEED;

    // Client-side PTZ easing state (not saved/synced)
    private float easedPan;
    private float easedTilt;
    private long lastEaseNanos;
    private boolean easeInit;

    // Client-side track cache
    private TrackPath trackPath;
    private long trackCacheTime = Long.MIN_VALUE;

    public NdiCameraBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.CAMERA_BE.get(), pos, state);
        tilt = defaultTilt();
    }

    /**
     * Resting tilt per rig type. A jib boom and a PTZ sit above the action and are
     * aimed down at it; a shoulder camera and a dolly start level.
     */
    private float defaultTilt() {
        return switch (getKind()) {
            case JIB -> -14.0F;
            case PTZ -> -8.0F;
            default -> 0.0F;
        };
    }

    public CameraKind getKind() {
        return getBlockState().getBlock() instanceof NdiCameraBlock cam ? cam.getKind() : CameraKind.BROADCAST;
    }

    public Direction getFacing() {
        return getBlockState().getValue(NdiCameraBlock.FACING);
    }

    // --- config accessors -------------------------------------------------

    public String getSourceName() {
        return sourceName;
    }

    /** The NDI sender name, with a per-position default when not configured. */
    public String getEffectiveSourceName() {
        if (!sourceName.isBlank()) {
            return sourceName;
        }
        String kind = switch (getKind()) {
            case BROADCAST -> "Cam";
            case PTZ -> "PTZ";
            case JIB -> "Jib";
            case TRACK -> "Dolly";
        };
        return "MC " + kind + " " + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    public boolean isActive() {
        return active;
    }

    public int getResolutionIndex() {
        return resolution;
    }

    public int getWidth() {
        return RES_W[resolution];
    }

    public int getHeight() {
        return RES_H[resolution];
    }

    public int getFps() {
        return fps;
    }

    public float getFov() {
        return fov;
    }

    public float getPan() {
        return pan;
    }

    public float getTilt() {
        return tilt;
    }

    public float getPtzSpeed() {
        return ptzSpeed;
    }

    public float getJibArmLength() {
        return jibArmLength;
    }

    public float getJibSweep() {
        return jibSweep;
    }

    public float getJibPeriod() {
        return jibPeriod;
    }

    public float getTrackSpeed() {
        return trackSpeed;
    }

    public void applyConfig(String sourceName, boolean active, int resolution, int fps, float fov,
                            float pan, float tilt, float aux1, float aux2, float aux3) {
        this.sourceName = Clamps.name(sourceName, MAX_SOURCE_NAME);
        this.active = active;
        this.resolution = Clamps.i(resolution, 0, RES_W.length - 1);
        this.fps = Clamps.i(fps, 10, 60);
        this.fov = Clamps.f(fov, 10.0F, 110.0F, DEFAULT_FOV);
        this.pan = Clamps.f(pan, -180.0F, 180.0F, 0.0F);
        this.tilt = Clamps.f(tilt, -85.0F, 85.0F, 0.0F);
        switch (getKind()) {
            case PTZ -> ptzSpeed = Clamps.f(aux1, 5.0F, 180.0F, DEFAULT_PTZ_SPEED);
            case JIB -> {
                jibArmLength = Clamps.f(aux1, 2.0F, MAX_JIB_ARM, DEFAULT_JIB_ARM);
                jibSweep = Clamps.f(aux2, 10.0F, 170.0F, DEFAULT_JIB_SWEEP);
                jibPeriod = Clamps.f(aux3, 4.0F, 40.0F, DEFAULT_JIB_PERIOD);
            }
            case TRACK -> trackSpeed = Clamps.f(aux1, 0.1F, 4.0F, DEFAULT_TRACK_SPEED);
            default -> {
            }
        }
        setChanged();
    }

    // --- view math --------------------------------------------------------

    private static float facingYaw(Direction facing) {
        return facing.toYRot();
    }

    /**
     * Seconds for the motion phase, continuous across frames and identical on every client
     * (it is derived from game time, so a jib sweep looks the same to everyone).
     *
     * The wrap exists to keep double precision in the sine arguments, and is large enough
     * that reaching it takes on the order of a year of continuous play — the old 1,000,000
     * tick wrap (about 14 hours) made the jib sweep and dolly visibly jump mid-show.
     */
    private static final long MOTION_WRAP_TICKS = 1_000_000_000L;

    private double motionSeconds(float partialTick) {
        long t = level != null ? level.getGameTime() : 0L;
        return (t % MOTION_WRAP_TICKS + partialTick) / 20.0;
    }

    /** Advances the PTZ head toward its target at the configured slew rate. Client only. */
    public float[] getEasedPanTilt() {
        long now = System.nanoTime();
        if (!easeInit) {
            easedPan = pan;
            easedTilt = tilt;
            lastEaseNanos = now;
            easeInit = true;
        }
        float dt = Math.min((now - lastEaseNanos) / 1_000_000_000.0F, 0.25F);
        lastEaseNanos = now;
        float step = ptzSpeed * dt;
        easedPan += Mth.clamp(pan - easedPan, -step, step);
        easedTilt += Mth.clamp(tilt - easedTilt, -step, step);
        return new float[]{easedPan, easedTilt};
    }

    /**
     * This jib's occupied seat, or null when nobody is aboard.
     *
     * Found by search rather than stored on the block: the seat already knows which jib it
     * belongs to and its state is synced to every client, so both sides can read the arm from it
     * with no extra packets and no per-tick block updates. The search is over a small box and
     * only matches this jib's own seat.
     */
    @Nullable
    public dev.nano.ndidisplays.entity.JibSeatEntity jibSeat() {
        if (level == null || getKind() != CameraKind.JIB) {
            return null;
        }
        net.minecraft.world.phys.AABB near =
                new net.minecraft.world.phys.AABB(worldPosition).inflate(MAX_JIB_ARM + 4.0);
        for (dev.nano.ndidisplays.entity.JibSeatEntity seat : level.getEntitiesOfClass(
                dev.nano.ndidisplays.entity.JibSeatEntity.class, near,
                e -> e.jibPos().equals(worldPosition))) {
            if (seat.getFirstPassenger() != null) {
                return seat;
            }
        }
        return null;
    }

    /**
     * Where the operator sits: just behind the pivot on the counterweight side, swinging with
     * the arm as a real crane seat does.
     *
     * Deliberately close to the pivot rather than out at the tip. The seat inherits the arm's
     * yaw, and at full extension the tip travels fast enough that riding it would fling the
     * operator around; near the pivot the motion reads as a crane seat instead.
     */
    public Vec3 getJibSeatPos(float partialTick) {
        Vec3 pivot = Vec3.atCenterOf(worldPosition).add(0, 1.05, 0);
        float[] arm = getJibArmAngles(partialTick);
        double rad = Math.toRadians(arm[0]);
        Vec3 forward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
        // Behind the pivot, a little below the arm — the counterweight end.
        return pivot.add(forward.scale(-1.6)).add(0.0, -0.35, 0.0);
    }

    /** Jib arm state: [armYawDeg, armElevDeg]. The arm sweeps and gently breathes. */
    public float[] getJibArmAngles(float partialTick) {
        double s = motionSeconds(partialTick);
        double w = (Math.PI * 2.0) / jibPeriod;
        dev.nano.ndidisplays.entity.JibSeatEntity seat = jibSeat();
        if (seat != null) {
            // Being flown: the seat owns the arm, integrated from the operator's WASD. Their look
            // direction is deliberately not involved, so they can watch the shot while driving the
            // crane. The automatic sweep is suspended entirely — a crane that kept oscillating
            // under the operator would be unusable.
            return seat.armAngles(partialTick);
        }
        float armYaw = facingYaw(getFacing()) + (float) (Math.sin(s * w) * jibSweep * 0.5);
        float armElev = 20.0F + (float) (Math.sin(s * w * 0.5 + 0.8) * 10.0);
        return new float[]{armYaw, armElev};
    }

    /**
     * Resolves the rail run under this dolly (client, cached ~2s). The run may bend or close
     * into a ring; {@link TrackPath} handles the geometry.
     */
    public void ensureTrackScan() {
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        if (trackCacheTime != Long.MIN_VALUE && now - trackCacheTime < 40) {
            return;
        }
        trackCacheTime = now;
        trackPath = TrackPath.build(level, worldPosition.below());
    }

    /**
     * Dolly world position along its rail. A closed ring of track carries the dolly round
     * continuously; an open run ping-pongs between its ends.
     */
    public Vec3 getDollyPos(float partialTick) {
        ensureTrackScan();
        if (trackPath == null || !trackPath.isUsable()) {
            return new Vec3(worldPosition.getX() + 0.5, worldPosition.getY(), worldPosition.getZ() + 0.5);
        }
        return trackPath.positionAt(motionSeconds(partialTick) * trackSpeed);
    }

    /**
     * The dolly's heading, following the rail through curves so the chassis and camera point
     * along the direction of travel rather than staying locked to the block's facing.
     * Falls back to the placed facing when there is no usable rail.
     */
    public float getDollyYaw(float partialTick) {
        ensureTrackScan();
        if (trackPath == null || !trackPath.isUsable()) {
            return facingYaw(getFacing());
        }
        return trackPath.yawAt(motionSeconds(partialTick) * trackSpeed);
    }

    /** The camera eye position + rotation for capture and for the renderer's head aim. */
    public ViewState getViewState(float partialTick) {
        Vec3 center = new Vec3(worldPosition.getX() + 0.5, worldPosition.getY(), worldPosition.getZ() + 0.5);
        float baseYaw = facingYaw(getFacing());
        switch (getKind()) {
            case BROADCAST -> {
                float yaw = baseYaw + pan;
                float pitch = -tilt;
                Vec3 fwd = Vec3.directionFromRotation(pitch, yaw);
                // Eye must clear the rig's own geometry: the renderer's body origin is at
                // y+1.25, its matte box spans local z 0.41-0.475, the lens glass front is
                // at 0.4785 and the drooping top flag reaches ~0.53. Sitting behind those
                // filled most of the feed with the inside of the matte box, because the
                // rig draws itself during its own capture and the near plane is only 0.05.
                return new ViewState(center.add(0, 1.265, 0).add(fwd.scale(0.55)), yaw, pitch);
            }
            case PTZ -> {
                float[] pt = getEasedPanTilt();
                float yaw = baseYaw + pt[0];
                float pitch = -pt[1];
                Vec3 fwd = Vec3.directionFromRotation(pitch, yaw);
                // Eye sits just in front of the lens (tilt pivot at y=0.50,
                // glass at local +Z 0.24) so the feed is not the inside of the barrel.
                return new ViewState(center.add(0, 0.50, 0).add(fwd.scale(0.26)), yaw, pitch);
            }
            case JIB -> {
                float[] arm = getJibArmAngles(partialTick);
                Vec3 pivot = center.add(0, 1.05, 0);
                Vec3 armDir = Vec3.directionFromRotation(-arm[1], arm[0]);
                Vec3 tip = pivot.add(armDir.scale(jibArmLength));
                float yaw = arm[0] + pan;
                float pitch = -tilt;
                // The remote head hangs at tip - (0, 0.175, 0) and its body box spans local
                // z -0.16..0.10 with the lens glass front at 0.188. The old eye (tip - 0.2)
                // sat *inside* that box, so the jib's feed was a solid grey rectangle.
                Vec3 fwd = Vec3.directionFromRotation(pitch, yaw);
                return new ViewState(tip.add(0, -0.175, 0).add(fwd.scale(0.21)), yaw, pitch);
            }
            case TRACK -> {
                // Pan is relative to the direction of travel, so a dolly on a curve keeps
                // its framing through the bend instead of swinging off the subject.
                Vec3 dolly = getDollyPos(partialTick);
                float yaw = getDollyYaw(partialTick) + pan;
                float pitch = -tilt;
                // The head rides the end of the slewing arm, so pan moves the eye as well as
                // aiming it — swinging the rig sweeps the camera through an arc, which is the
                // whole point of a motion-control crane.
                Vec3 reach = Vec3.directionFromRotation(0.0F, yaw).scale(MILO_HEAD_FORWARD);
                Vec3 head = dolly.add(0.0, MILO_HEAD_UP, 0.0).add(reach);
                Vec3 fwd = Vec3.directionFromRotation(pitch, yaw);
                // Ahead of the lens glass, so the head's own body is never in its shot.
                return new ViewState(head.add(fwd.scale(0.26)), yaw, pitch);
            }
        }
        return new ViewState(center.add(0, 1, 0), baseYaw, 0);
    }

    // --- NBT / sync -------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Source", sourceName);
        tag.putBoolean("Active", active);
        tag.putInt("Res", resolution);
        tag.putInt("Fps", fps);
        tag.putFloat("Fov", fov);
        tag.putFloat("Pan", pan);
        tag.putFloat("Tilt", tilt);
        tag.putFloat("PtzSpeed", ptzSpeed);
        tag.putFloat("JibLen", jibArmLength);
        tag.putFloat("JibSweep", jibSweep);
        tag.putFloat("JibPeriod", jibPeriod);
        tag.putFloat("TrackSpeed", trackSpeed);
    }

    /**
     * Also handles the client sync packet. Every value is re-clamped here rather than
     * trusted: a NaN angle would put the capture camera at an undefined position, and
     * a NaN period/length would make the jib and dolly motion maths collapse.
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        sourceName = Clamps.name(tag.getString("Source"), MAX_SOURCE_NAME);
        active = !tag.contains("Active") || tag.getBoolean("Active");
        resolution = Clamps.i(tag.contains("Res") ? tag.getInt("Res") : DEFAULT_RESOLUTION, 0, RES_W.length - 1);
        fps = Clamps.i(tag.contains("Fps") ? tag.getInt("Fps") : DEFAULT_FPS, 10, 60);
        fov = Clamps.f(tag.contains("Fov") ? tag.getFloat("Fov") : DEFAULT_FOV, 10.0F, 110.0F, DEFAULT_FOV);
        pan = Clamps.f(tag.getFloat("Pan"), -180.0F, 180.0F, 0.0F);
        // Only override the kind-specific tilt the constructor chose if the tag is really
        // present: CompoundTag.getFloat returns 0.0 for a missing key, which silently reset
        // the jib's -14 and the PTZ's -8 default to dead level, so those rigs framed the
        // horizon instead of the stage below them.
        if (tag.contains("Tilt")) {
            tilt = Clamps.f(tag.getFloat("Tilt"), -85.0F, 85.0F, defaultTilt());
        }
        ptzSpeed = Clamps.f(tag.contains("PtzSpeed") ? tag.getFloat("PtzSpeed") : DEFAULT_PTZ_SPEED,
                5.0F, 180.0F, DEFAULT_PTZ_SPEED);
        // MAX_JIB_ARM, not a literal: this load clamp still said 8 after the limit was raised,
        // so a longer arm survived the config packet and was then cut back the moment it
        // round-tripped through NBT — the value appeared to save and then silently reverted.
        jibArmLength = Clamps.f(tag.contains("JibLen") ? tag.getFloat("JibLen") : DEFAULT_JIB_ARM,
                2.0F, MAX_JIB_ARM, DEFAULT_JIB_ARM);
        jibSweep = Clamps.f(tag.contains("JibSweep") ? tag.getFloat("JibSweep") : DEFAULT_JIB_SWEEP,
                10.0F, 170.0F, DEFAULT_JIB_SWEEP);
        jibPeriod = Clamps.f(tag.contains("JibPeriod") ? tag.getFloat("JibPeriod") : DEFAULT_JIB_PERIOD,
                4.0F, 40.0F, DEFAULT_JIB_PERIOD);
        trackSpeed = Clamps.f(tag.contains("TrackSpeed") ? tag.getFloat("TrackSpeed") : DEFAULT_TRACK_SPEED,
                0.1F, 4.0F, DEFAULT_TRACK_SPEED);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) {
            load(packet.getTag());
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        // Jib arms extend by their arm length; a track dolly travels the whole length of its
        // rail, which can be far longer than any fixed inflation. A box that does not cover
        // where the rig is actually drawn gets frustum-culled, and the dolly (plus its tally)
        // vanishes while still clearly in view.
        AABB box = new AABB(worldPosition).inflate(Math.max(10.0, jibArmLength + 2.0));
        if (getKind() == CameraKind.TRACK) {
            ensureTrackScan();
            if (trackPath != null && trackPath.isUsable()) {
                // Cover the whole run, whatever shape it is, or the dolly is culled while
                // visibly on screen at the far side of a curve or ring.
                box = box.minmax(trackPath.bounds().inflate(2.0));
            }
        }
        return box;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            dev.nano.ndidisplays.client.CameraFeedManager.register(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            dev.nano.ndidisplays.client.CameraFeedManager.unregister(this);
        }
        super.setRemoved();
    }
}
