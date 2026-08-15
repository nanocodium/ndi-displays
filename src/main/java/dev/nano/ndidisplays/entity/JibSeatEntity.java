package dev.nano.ndidisplays.entity;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The operator's seat on a jib. An invisible entity the player rides, which is how Minecraft
 * expresses "sitting on something" — riding is the only mechanism that reparents a player's
 * position and lets a block move them, and it comes with dismount handling for free.
 *
 * The seat is owned by the jib block: it exists only while someone is riding, follows the
 * block's own arm angles every tick, and removes itself the moment the rider leaves or the jib
 * is broken. Nothing about it is persisted — a seat left behind by a crash is meaningless, and
 * respawning one on world load would strand players in mid-air.
 */
public class JibSeatEntity extends Entity {

    /** The jib this seat belongs to, synced so the client can follow the same arm. */
    private static final EntityDataAccessor<BlockPos> JIB_POS =
            SynchedEntityData.defineId(JibSeatEntity.class, EntityDataSerializers.BLOCK_POS);

    /**
     * Arm angles, owned by the seat rather than the block.
     *
     * The crane is flown with WASD, so the arm is state that has to be integrated from input
     * over time — not something derivable from where the operator happens to be looking. Keeping
     * it on the seat means it is synced to every client automatically and disappears with the
     * ride, instead of needing per-tick block updates.
     */
    private static final EntityDataAccessor<Float> ARM_YAW =
            SynchedEntityData.defineId(JibSeatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ARM_ELEV =
            SynchedEntityData.defineId(JibSeatEntity.class, EntityDataSerializers.FLOAT);

    /** Slew and boom rates, degrees per second — a crane moves deliberately, not instantly. */
    private static final float YAW_RATE = 55.0F;
    private static final float ELEV_RATE = 32.0F;
    private static final float MIN_ELEV = -35.0F;
    private static final float MAX_ELEV = 80.0F;

    /** Previous tick's angles, for smoothing the arm between ticks when rendering. */
    private float prevYaw;
    private float prevElev;

    public JibSeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static JibSeatEntity create(Level level, BlockPos jib) {
        JibSeatEntity seat = new JibSeatEntity(NdiDisplays.JIB_SEAT.get(), level);
        seat.entityData.set(JIB_POS, jib);
        seat.moveTo(Vec3.atCenterOf(jib));
        return seat;
    }

    public BlockPos jibPos() {
        return entityData.get(JIB_POS);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(JIB_POS, BlockPos.ZERO);
        entityData.define(ARM_YAW, 0.0F);
        entityData.define(ARM_ELEV, 20.0F);
    }

    /** Starts the arm where the jib is already pointing, so mounting does not snap it. */
    public void initArm(float yaw, float elev) {
        entityData.set(ARM_YAW, yaw);
        entityData.set(ARM_ELEV, elev);
        prevYaw = yaw;
        prevElev = elev;
    }

    /**
     * Arm angles for rendering, smoothed across the tick.
     *
     * @return {@code [yawDegrees, elevationDegrees]}
     */
    public float[] armAngles(float partialTick) {
        float yaw = net.minecraft.util.Mth.rotLerp(partialTick, prevYaw, entityData.get(ARM_YAW));
        float elev = net.minecraft.util.Mth.lerp(partialTick, prevElev, entityData.get(ARM_ELEV));
        return new float[]{yaw, elev};
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        // The seat is a projection of the jib, so the block drives it rather than the reverse.
        // No rider, or no jib, means the seat has nothing to be.
        if (getPassengers().isEmpty()) {
            discard();
            return;
        }
        if (!(level().getBlockEntity(jibPos()) instanceof NdiCameraBlockEntity jib)
                || jib.isRemoved()) {
            discard();
            return;
        }
        flyArm();
        Vec3 seat = jib.getJibSeatPos(1.0F);
        setPos(seat.x, seat.y, seat.z);
    }

    /**
     * Integrates the rider's WASD into arm movement: forward and back boom the arm up and down,
     * left and right slew it.
     *
     * The rider's movement input reaches the server as part of riding — the same channel a boat
     * reads to steer — so no extra packets are needed. Their own look direction is deliberately
     * ignored here: the operator needs to watch the shot while driving the crane, which is only
     * possible if looking around and flying the arm are separate.
     */
    private void flyArm() {
        if (!(getFirstPassenger() instanceof net.minecraft.world.entity.LivingEntity rider)) {
            return;
        }
        float dt = 1.0F / 20.0F;
        float yaw = entityData.get(ARM_YAW);
        float elev = entityData.get(ARM_ELEV);
        // xxa is strafe (A/D), zza is forward/back (W/S). Strafing left should swing the arm
        // left, which is a decreasing yaw in Minecraft's convention.
        if (rider.xxa != 0.0F) {
            yaw -= Math.signum(rider.xxa) * YAW_RATE * dt;
        }
        if (rider.zza != 0.0F) {
            elev += Math.signum(rider.zza) * ELEV_RATE * dt;
        }
        elev = Math.max(MIN_ELEV, Math.min(MAX_ELEV, elev));
        prevYaw = entityData.get(ARM_YAW);
        prevElev = entityData.get(ARM_ELEV);
        entityData.set(ARM_YAW, yaw);
        entityData.set(ARM_ELEV, elev);
    }

    @Override
    public void baseTick() {
        // Client side too, so the render lerp has a previous value to work from.
        if (level().isClientSide) {
            prevYaw = entityData.get(ARM_YAW);
            prevElev = entityData.get(ARM_ELEV);
        }
        super.baseTick();
    }

    /**
     * Where the rider sits relative to the entity. Zero because the seat entity is already
     * positioned at the operator's seat — adding an offset here would double it.
     */
    @Override
    public double getPassengersRidingOffset() {
        return 0.0;
    }

    /**
     * The rider steers the arm by looking, so their view must stay free. A plain Entity does not
     * clamp passenger rotation the way a boat or horse does, so nothing needs overriding here —
     * but it is worth stating, because it is what makes look-to-fly work.
     */

    @Override
    public boolean isPickable() {
        // Never clickable: the jib block itself is the thing you interact with, and an
        // invisible hitbox floating at the arm tip would only get in the way.
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // No getAddEntityPacket override on purpose. Forge's custom spawn packet needs a matching
    // custom client factory on the entity type, and without one the client cannot construct the
    // seat — the server seats the player while their client never sees a vehicle, which looks
    // exactly like the interaction doing nothing. The seat needs no constructor data: its jib and
    // arm angles are synched data, which arrives right after the vanilla spawn packet.
}
