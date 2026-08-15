package dev.nano.ndidisplays.entity;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.Clamps;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.item.DroneRemoteItem;
import dev.nano.ndidisplays.path.DronePath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Persistent NDI drone. Placed from an item, linked to a remote, flown by riding it.
 * The lens {@link NdiCameraBlockEntity.ViewState} is the same pose the FPV camera uses.
 */
public class DroneEntity extends Entity {

    public static final int MAX_SOURCE_NAME = 128;
    public static final float MIN_PITCH = -85.0F;
    public static final float MAX_PITCH = 30.0F;
    public static final float DEFAULT_MAX_SPEED = 8.0F;
    public static final double LENS_FORWARD = 0.28;
    public static final double LENS_UP = 0.10;

    private static final EntityDataAccessor<Float> HEADING =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> GIMBAL_PITCH =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> FLYING =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> LIVE =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> SOURCE_NAME =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> RESOLUTION =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FPS =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> FOV =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> MAX_SPEED =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<CompoundTag> PATH_TAG =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Boolean> PATH_PLAYING =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);

    private final DronePath path = new DronePath();
    private CompoundTag lastClientPath = new CompoundTag();
    private float prevHeading;
    private float prevPitch;
    private Vec3 velocity = Vec3.ZERO;
    @Nullable
    private Vec3 boardPos;
    private boolean hasPilotInput;
    private int inputGrace;
    private float inputForward;
    private float inputStrafe;
    private float inputVertical;
    private float inputYaw;
    private float inputPitch;

    public DroneEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
        this.noPhysics = false;
        this.setNoGravity(true);
    }

    public static DroneEntity create(Level level, Vec3 pos, float yaw) {
        DroneEntity drone = new DroneEntity(NdiDisplays.DRONE.get(), level);
        drone.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        drone.entityData.set(HEADING, yaw);
        drone.entityData.set(SOURCE_NAME, defaultSourceName(drone.getUUID()));
        return drone;
    }

    public static String defaultSourceName(UUID id) {
        String shortId = id.toString().substring(0, 8);
        return "MC Drone " + shortId;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(HEADING, 0.0F);
        entityData.define(GIMBAL_PITCH, 0.0F);
        entityData.define(FLYING, false);
        entityData.define(LIVE, true);
        entityData.define(SOURCE_NAME, "MC Drone");
        entityData.define(RESOLUTION, 1);
        entityData.define(FPS, 30);
        entityData.define(FOV, 70.0F);
        entityData.define(MAX_SPEED, DEFAULT_MAX_SPEED);
        entityData.define(PATH_TAG, new CompoundTag());
        entityData.define(PATH_PLAYING, false);
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (level().isClientSide) {
            dev.nano.ndidisplays.client.CameraFeedManager.registerDrone(this);
        }
    }

    @Override
    public void onRemovedFromWorld() {
        if (level().isClientSide) {
            dev.nano.ndidisplays.client.CameraFeedManager.unregisterDrone(this);
        }
        super.onRemovedFromWorld();
    }

    public float heading() {
        return entityData.get(HEADING);
    }

    public float gimbalPitch() {
        return entityData.get(GIMBAL_PITCH);
    }

    public float heading(float partialTick) {
        return Mth.rotLerp(partialTick, prevHeading, heading());
    }

    public float gimbalPitch(float partialTick) {
        return Mth.lerp(partialTick, prevPitch, gimbalPitch());
    }

    public boolean isFlying() {
        return entityData.get(FLYING);
    }

    public boolean isLive() {
        return entityData.get(LIVE);
    }

    public String getSourceName() {
        return entityData.get(SOURCE_NAME);
    }

    public String getEffectiveSourceName() {
        String name = getSourceName();
        return name == null || name.isBlank() ? defaultSourceName(getUUID()) : name;
    }

    public int getResolutionIndex() {
        return Clamps.i(entityData.get(RESOLUTION), 0, 2);
    }

    public int getWidth() {
        return NdiCameraBlockEntity.RES_W[getResolutionIndex()];
    }

    public int getHeight() {
        return NdiCameraBlockEntity.RES_H[getResolutionIndex()];
    }

    public int getFps() {
        return Clamps.i(entityData.get(FPS), 1, 60);
    }

    public float getFov() {
        return Clamps.f(entityData.get(FOV), 15.0F, 110.0F, 70.0F);
    }

    public float getMaxSpeed() {
        return Clamps.f(entityData.get(MAX_SPEED), 1.0F, 16.0F, DEFAULT_MAX_SPEED);
    }

    public DronePath path() {
        if (level().isClientSide) {
            CompoundTag tag = entityData.get(PATH_TAG);
            if (!tag.equals(lastClientPath)) {
                lastClientPath = tag.copy();
                path.load(tag);
            }
            if (entityData.get(PATH_PLAYING)) {
                if (!path.isPlaying() && path.size() >= 2) {
                    path.play();
                }
            } else if (path.isPlaying()) {
                path.stop();
            }
        }
        return path;
    }

    public void setGimbal(float heading, float pitch) {
        entityData.set(HEADING, Mth.wrapDegrees(heading));
        entityData.set(GIMBAL_PITCH, Mth.clamp(pitch, MIN_PITCH, MAX_PITCH));
    }

    public NdiCameraBlockEntity.ViewState viewState(float partialTick) {
        float yaw = heading(partialTick);
        float pitch = gimbalPitch(partialTick);
        Vec3 pos = position().add(0.0, LENS_UP, 0.0);
        if (partialTick != 1.0F) {
            pos = new Vec3(
                    Mth.lerp(partialTick, xo, getX()),
                    Mth.lerp(partialTick, yo, getY()) + LENS_UP,
                    Mth.lerp(partialTick, zo, getZ()));
        }
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        return new NdiCameraBlockEntity.ViewState(pos.add(forward.scale(LENS_FORWARD)), yaw, pitch);
    }

    public void applyPilotInput(float forward, float strafe, float vertical, float yaw, float pitch) {
        this.hasPilotInput = true;
        this.inputGrace = 8;
        this.inputForward = Mth.clamp(forward, -1.0F, 1.0F);
        this.inputStrafe = Mth.clamp(strafe, -1.0F, 1.0F);
        this.inputVertical = Mth.clamp(vertical, -1.0F, 1.0F);
        this.inputYaw = yaw;
        this.inputPitch = Mth.clamp(pitch, MIN_PITCH, MAX_PITCH);
    }

    public boolean enterPilot(Player player) {
        if (player.isPassenger() || isVehicle()) {
            return false;
        }
        boardPos = player.position();
        boolean seated = player.startRiding(this, true);
        if (seated) {
            player.displayClientMessage(Component.translatable("gui.ndidisplays.drone.piloting"), true);
        }
        return seated;
    }

    public void exitPilot(@Nullable Player player) {
        Player rider = player;
        if (rider == null && getFirstPassenger() instanceof Player p) {
            rider = p;
        }
        Vec3 dest = boardPos != null ? boardPos : position();
        if (rider != null && rider.getVehicle() == this) {
            rider.stopRiding();
        }
        if (rider != null && !level().isClientSide) {
            rider.teleportTo(dest.x, dest.y, dest.z);
        }
        boardPos = null;
        hasPilotInput = false;
        inputGrace = 0;
        landIfNearGround();
    }

    public void applyConfig(String source, boolean live, int resolution, int fps, float fov, float maxSpeed) {
        entityData.set(SOURCE_NAME, Clamps.name(source, MAX_SOURCE_NAME));
        entityData.set(LIVE, live);
        entityData.set(RESOLUTION, Clamps.i(resolution, 0, 2));
        int snapped = fps <= 24 ? 24 : fps <= 30 ? 30 : 60;
        entityData.set(FPS, snapped);
        entityData.set(FOV, Clamps.f(fov, 15.0F, 110.0F, 70.0F));
        entityData.set(MAX_SPEED, Clamps.f(maxSpeed, 1.0F, 16.0F, DEFAULT_MAX_SPEED));
    }

    public void syncPath() {
        entityData.set(PATH_TAG, path.save());
        entityData.set(PATH_PLAYING, path.isPlaying());
    }

    public void addWaypointHere() {
        NdiCameraBlockEntity.ViewState view = viewState(1.0F);
        path.add(new DronePath.Waypoint(position(), view.yaw(), view.pitch(),
                Math.max(1.0F, getMaxSpeed() * 0.5F), 0));
        syncPath();
    }

    @Override
    public void tick() {
        super.tick();
        if (path.isPlaying()) {
            if (level().isClientSide) {
                return;
            }
            DronePath.Sample sample = path.tick();
            if (sample != null) {
                entityData.set(FLYING, true);
                setGimbal(sample.yaw(), sample.pitch());
                setDeltaMovement(Vec3.ZERO);
                velocity = Vec3.ZERO;
                setPos(sample.pos().x, sample.pos().y, sample.pos().z);
                if (!entityData.get(PATH_PLAYING)) {
                    entityData.set(PATH_PLAYING, true);
                }
            } else if (!path.isPlaying()) {
                entityData.set(PATH_PLAYING, false);
                landIfNearGround();
            }
            return;
        }
        flyFromInput();
    }

    /**
     * Space / WASD come from the rider's vanilla input packet (same channel as a
     * boat). The custom stick packet is only a supplement for analog pads.
     */
    private void flyFromInput() {
        float forward = 0.0F;
        float strafe = 0.0F;
        float vertical = 0.0F;
        boolean piloted = getFirstPassenger() instanceof Player;
        if (getFirstPassenger() instanceof LivingEntity rider) {
            forward = rider.zza;
            strafe = rider.xxa;
            if (isJumping(rider)) {
                vertical = 1.0F;
            }
            if (hasPilotInput || inputGrace > 0) {
                forward = absMax(forward, inputForward);
                strafe = absMax(strafe, inputStrafe);
                vertical = absMax(vertical, inputVertical);
                setGimbal(inputYaw, inputPitch);
            } else {
                setGimbal(rider.getYRot(), rider.getXRot());
            }
        } else if (hasPilotInput || inputGrace > 0) {
            forward = inputForward;
            strafe = inputStrafe;
            vertical = inputVertical;
            setGimbal(inputYaw, inputPitch);
        }
        if (inputGrace > 0) {
            inputGrace--;
        }
        hasPilotInput = false;

        boolean commanded = piloted && (Math.abs(forward) > 0.01F
                || Math.abs(strafe) > 0.01F
                || Math.abs(vertical) > 0.01F);
        boolean climb = vertical > 0.05F;

        if (commanded || climb) {
            entityData.set(FLYING, true);
        }

        // Sticks or climb: hop off the ground. Without this a calibrated pad
        // "does nothing" because horizontal desired is applied while still
        // planted and the next sync snaps the drone back.
        if (commanded && (onGround() || verticalCollision)) {
            setPos(getX(), getY() + 0.35, getZ());
            velocity = new Vec3(velocity.x, Math.max(velocity.y, climb ? 6.0 : 3.5), velocity.z);
        }

        float max = getMaxSpeed();
        Vec3 desired = Vec3.ZERO;
        if (piloted && (isFlying() || commanded)) {
            float yawRad = (float) Math.toRadians(heading());
            Vec3 look = new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
            Vec3 right = new Vec3(look.z, 0.0, -look.x);
            desired = look.scale(forward).add(right.scale(strafe)).add(0.0, vertical, 0.0);
            if (desired.lengthSqr() > 1.0) {
                desired = desired.normalize();
            }
            desired = desired.scale(max);
        }

        if (isFlying()) {
            velocity = velocity.add(desired.subtract(velocity).scale(0.28));
            if (!piloted) {
                velocity = velocity.scale(0.90);
                if (onGround() && velocity.lengthSqr() < 0.04) {
                    velocity = Vec3.ZERO;
                    entityData.set(FLYING, false);
                }
            }
        } else {
            velocity = new Vec3(0.0, onGround() ? 0.0 : -0.08, 0.0);
        }

        move(MoverType.SELF, velocity.scale(1.0 / 20.0));
        setDeltaMovement(velocity.scale(1.0 / 20.0));
        if (horizontalCollision) {
            velocity = new Vec3(0.0, velocity.y, 0.0);
        }
        if (verticalCollision && velocity.y < 0.0 && !climb) {
            velocity = new Vec3(velocity.x, 0.0, velocity.z);
        }
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof LivingEntity living ? living : null;
    }

    private static float absMax(float a, float b) {
        return Math.abs(b) > Math.abs(a) ? b : a;
    }

    private static boolean isJumping(LivingEntity rider) {
        for (String name : new String[]{"jumping", "f_20899_"}) {
            try {
                var field = LivingEntity.class.getDeclaredField(name);
                field.setAccessible(true);
                if (field.getBoolean(rider)) {
                    return true;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private void landIfNearGround() {
        if (level().isClientSide) {
            return;
        }
        BlockHit ground = findGround();
        if (ground != null && getY() - ground.y <= 0.4) {
            setPos(getX(), ground.y + 0.02, getZ());
            entityData.set(FLYING, false);
            velocity = Vec3.ZERO;
            setDeltaMovement(Vec3.ZERO);
        }
    }

    private record BlockHit(double y) {
    }

    @Nullable
    private BlockHit findGround() {
        var pos = blockPosition();
        for (int dy = 0; dy <= 8; dy++) {
            var check = pos.below(dy);
            if (!level().getBlockState(check).getCollisionShape(level(), check).isEmpty()) {
                return new BlockHit(check.getY() + 1.0);
            }
        }
        return null;
    }

    @Override
    public void baseTick() {
        if (level().isClientSide) {
            prevHeading = heading();
            prevPitch = gimbalPitch();
        }
        super.baseTick();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof DroneRemoteItem) {
            if (!level().isClientSide) {
                DroneRemoteItem.link(stack, this);
                player.displayClientMessage(Component.translatable("gui.ndidisplays.drone.linked"), true);
                level().playSound(null, blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(),
                        SoundSource.PLAYERS, 0.6F, 1.4F);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (player.isShiftKeyDown() && stack.isEmpty() && !isVehicle()) {
            if (!level().isClientSide) {
                pickup(player);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    public void pickup(Player player) {
        if (isVehicle()) {
            return;
        }
        ItemStack stack = new ItemStack(NdiDisplays.DRONE_ITEM.get());
        if (!player.addItem(stack) && !player.getAbilities().instabuild) {
            player.drop(stack, false);
        }
        discard();
    }

    @Override
    protected void removePassenger(Entity passenger) {
        Vec3 dest = boardPos != null ? boardPos : position();
        super.removePassenger(passenger);
        if (!level().isClientSide && passenger instanceof Player) {
            passenger.teleportTo(dest.x, dest.y, dest.z);
            boardPos = null;
            landIfNearGround();
        }
    }

    /**
     * Keep the rider on the airframe, not buried in the floor. Putting them at
     * {@code lensY - eyeHeight} jammed the player into the block under the drone
     * and the vehicle could not take off. FPV is aimed at the lens on the client.
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        callback.accept(passenger, getX(), getY() + 0.08, getZ());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.02;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(HEADING, tag.getFloat("heading"));
        entityData.set(GIMBAL_PITCH, tag.getFloat("pitch"));
        entityData.set(FLYING, tag.getBoolean("flying"));
        entityData.set(LIVE, !tag.contains("live") || tag.getBoolean("live"));
        if (tag.contains("source")) {
            entityData.set(SOURCE_NAME, tag.getString("source"));
        }
        entityData.set(RESOLUTION, tag.getInt("resolution"));
        entityData.set(FPS, tag.contains("fps") ? tag.getInt("fps") : 30);
        entityData.set(FOV, tag.contains("fov") ? tag.getFloat("fov") : 70.0F);
        entityData.set(MAX_SPEED, tag.contains("maxSpeed") ? tag.getFloat("maxSpeed") : DEFAULT_MAX_SPEED);
        if (tag.contains("path")) {
            path.load(tag.getCompound("path"));
            syncPath();
        }
        if (tag.contains("boardX")) {
            boardPos = new Vec3(tag.getDouble("boardX"), tag.getDouble("boardY"), tag.getDouble("boardZ"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("heading", heading());
        tag.putFloat("pitch", gimbalPitch());
        tag.putBoolean("flying", isFlying());
        tag.putBoolean("live", isLive());
        tag.putString("source", getSourceName());
        tag.putInt("resolution", getResolutionIndex());
        tag.putInt("fps", getFps());
        tag.putFloat("fov", getFov());
        tag.putFloat("maxSpeed", getMaxSpeed());
        tag.put("path", path.save());
        if (boardPos != null) {
            tag.putDouble("boardX", boardPos.x);
            tag.putDouble("boardY", boardPos.y);
            tag.putDouble("boardZ", boardPos.z);
        }
    }

    @Nullable
    public static DroneEntity find(Level level, UUID id) {
        if (id == null) {
            return null;
        }
        if (level instanceof ServerLevel server) {
            Entity entity = server.getEntity(id);
            return entity instanceof DroneEntity drone ? drone : null;
        }
        return null;
    }
}
