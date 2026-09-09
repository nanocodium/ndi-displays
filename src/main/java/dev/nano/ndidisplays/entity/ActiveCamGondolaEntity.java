package dev.nano.ndidisplays.entity;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Kinematic gondola. The controller owns {@link ActiveCamPose}. The operator
 * rides this entity for FPV — same mechanism as the drone and the jib seat.
 */
public class ActiveCamGondolaEntity extends Entity {

    private static final EntityDataAccessor<BlockPos> CONTROLLER =
            SynchedEntityData.defineId(ActiveCamGondolaEntity.class, EntityDataSerializers.BLOCK_POS);

    public ActiveCamGondolaEntity(EntityType<? extends ActiveCamGondolaEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setSilent(true);
        noCulling = true;
    }

    public static ActiveCamGondolaEntity create(Level level, BlockPos controller, Vec3 pos) {
        ActiveCamGondolaEntity entity = NdiDisplays.ACTIVE_CAM.get().create(level);
        if (entity == null) {
            throw new IllegalStateException("Active Cam entity type missing");
        }
        entity.setController(controller);
        entity.setPos(pos.x, pos.y, pos.z);
        entity.xo = pos.x;
        entity.yo = pos.y;
        entity.zo = pos.z;
        return entity;
    }

    public void setController(BlockPos pos) {
        entityData.set(CONTROLLER, pos.immutable());
    }

    public BlockPos controllerPos() {
        return entityData.get(CONTROLLER);
    }

    @Nullable
    public ActiveCamControllerBlockEntity controller() {
        if (level().getBlockEntity(controllerPos()) instanceof ActiveCamControllerBlockEntity ctrl) {
            return ctrl;
        }
        return null;
    }

    public void snapTo(ActiveCamPose pose) {
        setPos(pose.x, pose.y, pose.z);
        xo = pose.x;
        yo = pose.y;
        zo = pose.z;
        setDeltaMovement(Vec3.ZERO);
        for (Entity passenger : getPassengers()) {
            positionRider(passenger, Entity::setPos);
        }
    }

    public void ejectOperator(@Nullable Player player, @Nullable Vec3 desk) {
        Player rider = player;
        if (rider == null && getFirstPassenger() instanceof Player p) {
            rider = p;
        }
        Vec3 dest = desk != null ? desk : position();
        if (rider != null && rider.getVehicle() == this) {
            rider.stopRiding();
        }
        if (rider != null && !level().isClientSide) {
            rider.teleportTo(dest.x, dest.y, dest.z);
        }
    }

    @Override
    public void tick() {
        super.tick();
        noPhysics = true;
        setDeltaMovement(Vec3.ZERO);
        if (!level().isClientSide) {
            ActiveCamControllerBlockEntity ctrl = controller();
            if (ctrl == null || !ctrl.isBound()) {
                if (getFirstPassenger() instanceof Player rider) {
                    ejectOperator(rider, null);
                }
                discard();
            }
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(CONTROLLER, BlockPos.ZERO);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Controller")) {
            setController(BlockPos.of(tag.getLong("Controller")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("Controller", controllerPos().asLong());
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        callback.accept(passenger, getX(), getY(), getZ());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.0;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide && passenger instanceof Player player) {
            ActiveCamControllerBlockEntity ctrl = controller();
            if (ctrl != null && player.getUUID().equals(ctrl.getOperatorId())) {
                ctrl.releaseControl(player);
            }
        }
    }

    @Override
    public boolean isPickable() {
        return false;
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
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256.0 * 256.0;
    }
}
