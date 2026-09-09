package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.activecam.ActiveCamBounds;
import dev.nano.ndidisplays.activecam.ActiveCamHeadMode;
import dev.nano.ndidisplays.activecam.ActiveCamKinematics;
import dev.nano.ndidisplays.activecam.ActiveCamLook;
import dev.nano.ndidisplays.activecam.ActiveCamMode;
import dev.nano.ndidisplays.activecam.ActiveCamPath;
import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.path.DronePath;
import dev.nano.ndidisplays.entity.ActiveCamGondolaEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ActiveCamControllerBlockEntity extends BlockEntity {

    public static final int MAX_SOURCE_NAME = 128;
    public static final float MIN_SPEED = 0.5F;
    public static final float MAX_SPEED = 25.0F;
    public static final float DEFAULT_SPEED = 10.0F;
    public static final float MIN_ACCEL = 0.5F;
    public static final float MAX_ACCEL = 15.0F;
    public static final float DEFAULT_ACCEL = 3.0F;
    public static final float DEFAULT_HEIGHT = 18.0F;
    public static final float LOOK_DEG_PER_SEC = 120.0F;
    public static final float ZOOM_PER_SEC = 40.0F;
    public static final int SNAPSHOT_INTERVAL = 2;

    private final BlockPos[] winches = new BlockPos[4];
    private final ActiveCamPose pose = new ActiveCamPose();
    private final ActiveCamPath path = new ActiveCamPath();
    private final DronePath waypoints = camPath();

    private static DronePath camPath() {
        DronePath waypoints = new DronePath();
        waypoints.setDownLook(true);
        return waypoints;
    }

    private ActiveCamMode mode = ActiveCamMode.TWO_D;
    private ActiveCamHeadMode liveHead = ActiveCamHeadMode.RECORDED;
    @Nullable
    private UUID trackTarget;
    @Nullable
    private UUID gondolaId;
    @Nullable
    private UUID operatorId;

    private String sourceName = "";
    private boolean live = false;
    private boolean eStop;
    private int resolution = 1;
    private int fps = 30;
    private float workingHeight = DEFAULT_HEIGHT;
    private float maxSpeed = DEFAULT_SPEED;
    private float acceleration = DEFAULT_ACCEL;
    private float maxCable = (float) ActiveCamBounds.DEFAULT_MAX_CABLE;

    private float moveX;
    private float moveZ;
    private float moveY;
    private float lookYaw;
    private float lookPitch;
    private float zoomAxis;
    private int inputGrace;
    private int snapshotAge;
    private int dismountGrace;
    private boolean ejecting;
    private boolean posePlaced;
    @Nullable
    private Vec3 boardPos;

    public ActiveCamControllerBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.ACTIVE_CAM_CONTROLLER_BE.get(), pos, state);
    }

    public boolean isBound() {
        for (BlockPos w : winches) {
            if (w == null) {
                return false;
            }
        }
        return true;
    }

    public BlockPos[] winches() {
        return winches;
    }

    public boolean winchConnected(Level level, int i) {
        BlockPos w = winches[i];
        return w != null && level.isLoaded(w)
                && level.getBlockEntity(w) instanceof CameraWinchBlockEntity;
    }

    public ActiveCamPose pose() {
        return pose;
    }

    public ActiveCamPath path() {
        return path;
    }

    public DronePath waypoints() {
        return waypoints;
    }

    public void addWaypointHere() {
        waypoints.addCam(new DronePath.Waypoint(pose.position(), pose.pan, pose.tilt, maxSpeed, 0));
        setChanged();
    }

    public ActiveCamMode mode() {
        return mode;
    }

    public ActiveCamHeadMode liveHead() {
        return liveHead;
    }

    @Nullable
    public UUID trackTarget() {
        return trackTarget;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getEffectiveSourceName() {
        if (sourceName != null && !sourceName.isBlank()) {
            return sourceName;
        }
        return "MC ActiveCam " + worldPosition.getX() + "," + worldPosition.getY() + ","
                + worldPosition.getZ();
    }

    public boolean isLiveRequested() {
        return live;
    }

    public boolean isLive() {
        return live && !eStop && isBound();
    }

    public boolean isEStop() {
        return eStop;
    }

    public int getResolution() {
        return resolution;
    }

    public int getWidth() {
        return NdiCameraBlockEntity.RES_W[Mth.clamp(resolution, 0, 2)];
    }

    public int getHeight() {
        return NdiCameraBlockEntity.RES_H[Mth.clamp(resolution, 0, 2)];
    }

    public int getFps() {
        return fps;
    }

    public float getWorkingHeight() {
        return workingHeight;
    }

    public float getMaxSpeed() {
        return maxSpeed;
    }

    public float getAcceleration() {
        return acceleration;
    }

    @Nullable
    public UUID getOperatorId() {
        return operatorId;
    }

    public NdiCameraBlockEntity.ViewState viewState() {
        return new NdiCameraBlockEntity.ViewState(pose.position(), pose.pan, pose.tilt);
    }

    @Nullable
    public ActiveCamBounds bounds(Level level) {
        if (!isBound()) {
            return null;
        }
        List<BlockPos> list = new ArrayList<>(4);
        for (BlockPos w : winches) {
            if (w == null || !level.isLoaded(w)
                    || !(level.getBlockEntity(w) instanceof CameraWinchBlockEntity)) {
                return null;
            }
            list.add(w);
        }
        return ActiveCamBounds.ofBlocks(list, maxCable);
    }

    public static void tick(Level level, BlockPos pos, BlockState state,
                            ActiveCamControllerBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        be.serverTick((ServerLevel) level);
    }

    private void serverTick(ServerLevel level) {
        ActiveCamBounds box = bounds(level);
        if (box == null) {
            if (isBound()) {
                emergencyStop();
            }
            return;
        }
        ensureGondola(level, box);
        if (dismountGrace > 0) {
            dismountGrace--;
        }
        boolean fromPacket = inputGrace > 0;
        if (inputGrace > 0) {
            inputGrace--;
        }
        // Packet already has look-relative XZ. Do not also remap rider zza — the two
        // sources fought and the gondola jittered or drifted on the opposite axis.
        if (!fromPacket && !readRiderInput(level)) {
            moveX = moveZ = moveY = lookYaw = lookPitch = zoomAxis = 0.0F;
        }

        relinkWinches(level);

        if (eStop) {
            pose.vx = pose.vy = pose.vz = 0.0;
            syncGondola(level);
            maybeSnapshot(level);
            return;
        }

        DronePath.Sample sample = waypoints.isPlaying() ? waypoints.tick() : null;
        if (sample != null) {
            ActiveCamKinematics.stepToward(pose, sample.pos(), maxSpeed, acceleration);
            pose.pan = sample.yaw();
            pose.tilt = sample.pitch();
            workingHeight = (float) box.clampHeight(pose.y);
        } else if (!waypoints.isPlaying() && !waypoints.isPaused()) {
            if (Math.abs(moveY) > 0.02F) {
                workingHeight = (float) box.clampHeight(
                        workingHeight + moveY * maxSpeed * ActiveCamKinematics.DT);
            }
            double height = box.clampHeight(workingHeight);
            boolean stick = Math.abs(moveX) > 0.02F || Math.abs(moveZ) > 0.02F;
            if (stick) {
                ActiveCamKinematics.stepVelocityXZ(pose, moveX * maxSpeed, moveZ * maxSpeed,
                        maxSpeed, acceleration);
            } else {
                ActiveCamKinematics.dampXZ(pose, acceleration);
            }
            ActiveCamKinematics.stepY(pose, height, maxSpeed, acceleration);
            applyHead(level, liveHead, trackTarget, true);
        }

        Vec3 before = pose.position();
        Vec3 clamped = box.clamp(before);
        if (Math.abs(clamped.x - before.x) > 1.0e-4) {
            pose.vx = 0.0;
        }
        if (Math.abs(clamped.y - before.y) > 1.0e-4) {
            pose.vy = 0.0;
        }
        if (Math.abs(clamped.z - before.z) > 1.0e-4) {
            pose.vz = 0.0;
        }
        pose.setPosition(clamped);
        syncGondola(level);
        maybeSnapshot(level);
    }

    /**
     * Stick XZ uses velocity integration; Y always eases to workingHeight.
     * Combined in one pass so a height change never teleports.
     */
    private void applyHead(ServerLevel level, ActiveCamHeadMode headMode,
                           @Nullable UUID targetId, boolean liveSlew) {
        if (headMode == ActiveCamHeadMode.TRACK_TARGET && targetId != null) {
            Entity entity = level.getEntity(targetId);
            if (entity == null) {
                for (Player player : level.players()) {
                    if (player.getUUID().equals(targetId)) {
                        entity = player;
                        break;
                    }
                }
            }
            if (entity != null) {
                ActiveCamLook.lookAt(pose, pose.position(), entity.getEyePosition());
                return;
            }
        }
        if (liveSlew && (Math.abs(lookYaw) > 1.0e-4F || Math.abs(lookPitch) > 1.0e-4F
                || Math.abs(zoomAxis) > 1.0e-4F)) {
            pose.fov = Mth.clamp(pose.fov + zoomAxis, 15.0F, 100.0F);
            lookYaw = lookPitch = zoomAxis = 0.0F;
        }
    }

    public void applyInput(float moveX, float moveZ, float moveY, float lookYaw, float lookPitch,
                           float zoom, UUID sender) {
        if (eStop || waypoints.isPlaying() || waypoints.isPaused()) {
            return;
        }
        if (operatorId != null && !operatorId.equals(sender)) {
            return;
        }
        this.moveX = Mth.clamp(moveX, -1.0F, 1.0F);
        this.moveZ = Mth.clamp(moveZ, -1.0F, 1.0F);
        this.moveY = Mth.clamp(moveY, -1.0F, 1.0F);
        this.pose.pan = lookYaw;
        this.pose.tilt = Mth.clamp(lookPitch, -89.0F, 89.0F);
        this.pose.fov = Mth.clamp(this.pose.fov + zoom, 15.0F, 100.0F);
        this.inputGrace = 8;
    }

    private boolean readRiderInput(ServerLevel level) {
        if (gondolaId == null) {
            return false;
        }
        Entity entity = level.getEntity(gondolaId);
        if (!(entity instanceof ActiveCamGondolaEntity gondola)
                || !(gondola.getFirstPassenger() instanceof Player player)) {
            return false;
        }
        float forward = player.zza;
        float strafe = player.xxa;
        float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);
        this.moveX = Mth.clamp(-sin * forward + cos * strafe, -1.0F, 1.0F);
        this.moveZ = Mth.clamp(cos * forward + sin * strafe, -1.0F, 1.0F);
        this.pose.pan = player.getYRot();
        this.pose.tilt = Mth.clamp(player.getXRot(), -89.0F, 89.0F);
        return true;
    }

    public boolean isDismountBlocked() {
        return !ejecting && dismountGrace > 0;
    }

    public void takeControl(ServerPlayer player) {
        this.operatorId = player.getUUID();
        this.boardPos = player.position();
        this.eStop = false;
        this.dismountGrace = 40;
        player.setShiftKeyDown(false);
        if (level instanceof ServerLevel sl) {
            ActiveCamBounds box = bounds(sl);
            if (box != null) {
                ensureGondola(sl, box);
                Entity entity = sl.getEntity(gondolaId);
                if (entity instanceof ActiveCamGondolaEntity gondola) {
                    gondola.snapTo(pose);
                    player.teleportTo(pose.x, pose.y, pose.z);
                    player.startRiding(gondola, true);
                }
            }
        }
        setChanged();
    }

    public void releaseControl(@Nullable Player player) {
        operatorId = null;
        Vec3 dest = boardPos;
        boardPos = null;
        ejecting = true;
        if (level instanceof ServerLevel sl && gondolaId != null) {
            Entity entity = sl.getEntity(gondolaId);
            if (entity instanceof ActiveCamGondolaEntity gondola) {
                Player rider = player;
                if (rider == null && gondola.getFirstPassenger() instanceof Player p) {
                    rider = p;
                }
                if (rider != null && rider.getVehicle() == gondola) {
                    rider.stopRiding();
                }
                if (rider != null) {
                    Vec3 to = dest != null ? dest : rider.position();
                    rider.teleportTo(to.x, to.y, to.z);
                }
            }
        }
        ejecting = false;
        setChanged();
    }

    public void emergencyStop() {
        eStop = true;
        pose.vx = pose.vy = pose.vz = 0.0;
        path.stop();
        waypoints.stop();
        moveX = moveZ = moveY = 0.0F;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    public void clearEStop() {
        eStop = false;
        setChanged();
    }

    public void onWinchRemoved(BlockPos winch) {
        emergencyStop();
    }

    public boolean bindWinches(BlockPos[] corners) {
        if (corners.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                if (corners[i].equals(corners[j])) {
                    return false;
                }
            }
        }
        unbindWinches();
        System.arraycopy(corners, 0, winches, 0, 4);
        posePlaced = false;
        eStop = false;
        setChanged();
        return true;
    }

    public void unbindWinches() {
        if (level != null) {
            for (BlockPos w : winches) {
                if (w != null && level.getBlockEntity(w) instanceof CameraWinchBlockEntity be
                        && worldPosition.equals(be.getControllerPos())) {
                    be.unbind();
                    BlockState st = level.getBlockState(w);
                    level.sendBlockUpdated(w, st, st, 3);
                }
            }
            if (gondolaId != null && level instanceof ServerLevel sl) {
                Entity e = sl.getEntity(gondolaId);
                if (e != null) {
                    e.discard();
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            winches[i] = null;
        }
        gondolaId = null;
        posePlaced = false;
        setChanged();
    }

    private void ensureGondola(ServerLevel level, ActiveCamBounds box) {
        if (!posePlaced) {
            workingHeight = (float) box.maxY;
            Vec3 c = box.center(workingHeight);
            pose.setPosition(c);
            pose.setVelocity(Vec3.ZERO);
            pose.fov = 60.0F;
            posePlaced = true;
            snapshotAge = SNAPSHOT_INTERVAL;
        }
        Entity existing = gondolaId == null ? null : level.getEntity(gondolaId);
        if (existing instanceof ActiveCamGondolaEntity) {
            return;
        }
        ActiveCamGondolaEntity spawned = ActiveCamGondolaEntity.create(level, worldPosition, pose.position());
        level.addFreshEntity(spawned);
        gondolaId = spawned.getUUID();
        setChanged();
    }

    private void relinkWinches(ServerLevel level) {
        for (BlockPos w : winches) {
            if (w == null || !level.isLoaded(w)) {
                continue;
            }
            if (level.getBlockEntity(w) instanceof CameraWinchBlockEntity winch
                    && !worldPosition.equals(winch.getControllerPos())) {
                winch.bind(worldPosition);
                BlockState st = level.getBlockState(w);
                level.sendBlockUpdated(w, st, st, 3);
            }
        }
    }

    private void syncGondola(ServerLevel level) {
        if (gondolaId == null) {
            return;
        }
        Entity entity = level.getEntity(gondolaId);
        if (entity instanceof ActiveCamGondolaEntity gondola
                && (gondola.isVehicle() || operatorId != null)) {
            gondola.snapTo(pose);
        }
    }

    private void maybeSnapshot(ServerLevel level) {
        int interval = operatorId != null ? 1 : SNAPSHOT_INTERVAL;
        if (++snapshotAge < interval) {
            return;
        }
        snapshotAge = 0;
        dev.nano.ndidisplays.net.ActiveCamSnapshotPacket.send(level, this);
    }

    public void applyConfig(String source, boolean live, int resolution, int fps,
                            float workingHeight, float maxSpeed, float acceleration,
                            ActiveCamMode mode, ActiveCamHeadMode head, @Nullable UUID target) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.live = live;
        this.resolution = Clamps.i(resolution, 0, 2);
        this.fps = Clamps.i(fps, 10, 60);
        this.workingHeight = Clamps.f(workingHeight, -64.0F, 320.0F, DEFAULT_HEIGHT);
        this.maxSpeed = Clamps.f(maxSpeed, MIN_SPEED, MAX_SPEED, DEFAULT_SPEED);
        this.acceleration = Clamps.f(acceleration, MIN_ACCEL, MAX_ACCEL, DEFAULT_ACCEL);
        this.mode = mode == ActiveCamMode.THREE_D ? ActiveCamMode.TWO_D : ActiveCamMode.TWO_D;
        this.liveHead = head == null ? ActiveCamHeadMode.RECORDED : head;
        this.trackTarget = target;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    public void setRemoved() {
        unbindWinches();
        if (level != null && level.isClientSide) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.nano.ndidisplays.client.CameraFeedManager.unregisterActiveCam(this));
        }
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.nano.ndidisplays.client.CameraFeedManager.registerActiveCam(this));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (int i = 0; i < 4; i++) {
            CompoundTag wt = new CompoundTag();
            if (winches[i] != null) {
                wt.putLong("p", winches[i].asLong());
            }
            list.add(wt);
        }
        tag.put("Winches", list);
        tag.put("Pose", pose.save());
        tag.put("Path", path.save());
        tag.put("Waypoints", waypoints.save());
        tag.putString("Source", sourceName);
        tag.putBoolean("Live", live);
        tag.putBoolean("EStop", eStop);
        tag.putInt("Res", resolution);
        tag.putInt("Fps", fps);
        tag.putFloat("Height", workingHeight);
        tag.putFloat("Speed", maxSpeed);
        tag.putFloat("Accel", acceleration);
        tag.putFloat("MaxCable", maxCable);
        tag.putString("Mode", mode.name());
        tag.putString("Head", liveHead.name());
        tag.putBoolean("Placed", posePlaced);
        if (trackTarget != null) {
            tag.putUUID("Track", trackTarget);
        }
        if (gondolaId != null) {
            tag.putUUID("Gondola", gondolaId);
        }
        if (operatorId != null) {
            tag.putUUID("Operator", operatorId);
        }
        if (boardPos != null) {
            tag.putDouble("BoardX", boardPos.x);
            tag.putDouble("BoardY", boardPos.y);
            tag.putDouble("BoardZ", boardPos.z);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ListTag list = tag.getList("Winches", Tag.TAG_COMPOUND);
        for (int i = 0; i < 4; i++) {
            winches[i] = null;
            if (i < list.size() && list.getCompound(i).contains("p")) {
                winches[i] = BlockPos.of(list.getCompound(i).getLong("p"));
            }
        }
        if (tag.contains("Pose")) {
            pose.set(ActiveCamPose.load(tag.getCompound("Pose")));
        }
        if (tag.contains("Path")) {
            path.load(tag.getCompound("Path"));
        }
        if (tag.contains("Waypoints")) {
            waypoints.load(tag.getCompound("Waypoints"));
        }
        waypoints.setDownLook(true);
        sourceName = tag.getString("Source");
        live = tag.getBoolean("Live");
        eStop = tag.getBoolean("EStop");
        resolution = tag.getInt("Res");
        fps = tag.contains("Fps") ? tag.getInt("Fps") : 30;
        workingHeight = tag.contains("Height") ? tag.getFloat("Height") : DEFAULT_HEIGHT;
        maxSpeed = tag.contains("Speed") ? tag.getFloat("Speed") : DEFAULT_SPEED;
        acceleration = tag.contains("Accel") ? tag.getFloat("Accel") : DEFAULT_ACCEL;
        maxCable = tag.contains("MaxCable") ? tag.getFloat("MaxCable") : (float) ActiveCamBounds.DEFAULT_MAX_CABLE;
        try {
            mode = ActiveCamMode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException ignored) {
            mode = ActiveCamMode.TWO_D;
        }
        try {
            liveHead = ActiveCamHeadMode.valueOf(tag.getString("Head"));
        } catch (IllegalArgumentException ignored) {
            liveHead = ActiveCamHeadMode.RECORDED;
        }
        posePlaced = tag.getBoolean("Placed");
        trackTarget = tag.hasUUID("Track") ? tag.getUUID("Track") : null;
        gondolaId = tag.hasUUID("Gondola") ? tag.getUUID("Gondola") : null;
        operatorId = tag.hasUUID("Operator") ? tag.getUUID("Operator") : null;
        if (tag.contains("BoardX")) {
            boardPos = new Vec3(tag.getDouble("BoardX"), tag.getDouble("BoardY"), tag.getDouble("BoardZ"));
        } else {
            boardPos = null;
        }
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
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            load(pkt.getTag());
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(96.0);
    }
}
