package dev.nano.ndidisplays.entity;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import dev.nano.ndidisplays.compat.theatrical.HoistFixtureCompat;
import dev.nano.ndidisplays.hoist.RigCollisionDetector;
import dev.nano.ndidisplays.hoist.RigRegistry;
import dev.nano.ndidisplays.hoist.RigStructure;
import dev.nano.ndidisplays.hoist.RigTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * A load in the air.
 *
 * While a structure is flying, its blocks are not in the world — they are here, as a
 * {@link RigStructure} snapshot, and this entity is the one and only copy. The entity's
 * position is the rig origin, so moving it moves the whole load, and the client renders
 * the snapshot at that position with ordinary entity interpolation. No blocks are set,
 * broken or replaced while the hoist runs; the world is touched exactly twice, once to
 * pick the load up and once to put it down.
 *
 * <h3>Height is the position, slope is synced state</h3>
 * Vertical travel rides on the entity's own position, which is what gives the load free
 * interpolation between the sparse packets. Slope cannot: it is carried as a gradient in
 * the synched data, along with the pivot the load turns about. A load only slopes when its
 * motors are at different heights, so both are cheap and change rarely.
 *
 * The entity is not the boss of anything. The owning {@link ChainHoistBlockEntity} drives
 * it, and if that motor is gone when this entity next ticks, the load lands where it is
 * rather than staying in the sky.
 */
public class MovingRigEntity extends Entity {

    private static final Logger LOG = LogUtils.getLogger();

    /** The captured load, models only. Sent once, when the rig is assembled. */
    private static final EntityDataAccessor<CompoundTag> STRUCTURE =
            SynchedEntityData.defineId(MovingRigEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Where the load was picked up; block offsets in the snapshot are relative to this. */
    private static final EntityDataAccessor<BlockPos> ORIGIN =
            SynchedEntityData.defineId(MovingRigEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Optional<UUID>> RIG_ID =
            SynchedEntityData.defineId(MovingRigEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /** Slope of the load as (dy/dx, unused, dy/dz). Zero for a level rig. */
    private static final EntityDataAccessor<Vector3f> GRADIENT =
            SynchedEntityData.defineId(MovingRigEntity.class, EntityDataSerializers.VECTOR3);
    /** Point the load turns about, in snapshot coordinates. */
    private static final EntityDataAccessor<Vector3f> PIVOT =
            SynchedEntityData.defineId(MovingRigEntity.class, EntityDataSerializers.VECTOR3);
    /** Live DMX state of any Theatrical fixtures riding along. Empty when there are none. */
    private static final EntityDataAccessor<CompoundTag> FIXTURES =
            SynchedEntityData.defineId(MovingRigEntity.class, EntityDataSerializers.COMPOUND_TAG);

    @Nullable
    private RigStructure structure;
    /** Set when the synched snapshot changes, so the client parses it once, not per frame. */
    private boolean structureDirty;

    /** Server-side authority. The client derives its own from position plus the gradient. */
    private RigTransform transform = RigTransform.IDENTITY;

    /** True once the load has been put back; stops a second repose from any other path. */
    private boolean landed;

    public MovingRigEntity(EntityType<? extends MovingRigEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.noCulling = true;
    }

    /**
     * Creates a rig entity carrying {@code structure}. Only the owning motor may call
     * this, and only after {@link RigRegistry#tryClaim} has succeeded.
     */
    public static MovingRigEntity create(ServerLevel level, UUID rigId, RigStructure structure) {
        MovingRigEntity entity = new MovingRigEntity(NdiDisplays.MOVING_RIG.get(), level);
        entity.structure = structure;
        entity.entityData.set(STRUCTURE, structure.saveForClient());
        entity.entityData.set(ORIGIN, structure.origin());
        entity.entityData.set(RIG_ID, Optional.of(rigId));
        BlockPos origin = structure.origin();
        entity.setPos(origin.getX(), origin.getY(), origin.getZ());
        return entity;
    }

    // ------------------------------------------------------------------ state

    @Nullable
    public UUID rigId() {
        return entityData.get(RIG_ID).orElse(null);
    }

    public BlockPos origin() {
        return entityData.get(ORIGIN);
    }

    /**
     * The snapshot.
     *
     * The server holds the authoritative one, with every block entity's NBT. The client
     * parses the models-only copy out of the synched tag, once per rig rather than once
     * per frame — it has nothing to restore, only something to draw.
     */
    @Nullable
    public RigStructure structure() {
        if (level().isClientSide && (structure == null || structureDirty)) {
            CompoundTag tag = entityData.get(STRUCTURE);
            if (tag.isEmpty()) {
                return structure;
            }
            structure = RigStructure.load(level(), tag);
            structureDirty = false;
        }
        return structure;
    }

    /** Metres the load has risen since it was picked up. Negative means it went down. */
    public double travel() {
        return getY() - origin().getY();
    }

    /** Where the load is being held, as the server sees it. */
    public RigTransform transform() {
        return transform;
    }

    /**
     * Rotation-only transform for a pose that is already sitting at the entity's
     * interpolated position, which is every renderer's situation. Applying the vertical
     * travel again there would count it twice.
     */
    public RigTransform renderTransform() {
        Vector3f gradient = entityData.get(GRADIENT);
        Vector3f pivot = entityData.get(PIVOT);
        return new RigTransform(0, gradient.x(), gradient.z(),
                pivot.x(), pivot.y(), pivot.z());
    }

    /** Live head state of the flown fixtures, empty when the load carries no lights. */
    public CompoundTag fixtureState() {
        return entityData.get(FIXTURES);
    }

    /** Moves the load. Called by the owning motor once per server tick. */
    public void setTransform(RigTransform next) {
        this.transform = next;
        BlockPos origin = origin();
        setPos(origin.getX(), origin.getY() + next.travelY(), origin.getZ());

        Vector3f gradient = new Vector3f((float) next.gradX(), 0.0F, (float) next.gradZ());
        if (!gradient.equals(entityData.get(GRADIENT))) {
            entityData.set(GRADIENT, gradient);
        }
        Vector3f pivot = new Vector3f((float) next.pivotX(), (float) next.pivotY(),
                (float) next.pivotZ());
        if (!pivot.equals(entityData.get(PIVOT))) {
            entityData.set(PIVOT, pivot);
        }
    }

    /** True when this rig will still take instructions — it has a load and is airborne. */
    public boolean isCarrying() {
        return !landed && structure() != null && structure().size() > 0;
    }

    // ------------------------------------------------------------------ landing

    /**
     * Puts the load back into the world at its current height and removes this entity.
     *
     * This is the only way a rig stops existing with its cargo intact, and it is
     * idempotent: whatever calls it — the operator, a fault, a mined motor, a chunk
     * reload — the load lands once.
     *
     * A sloped load cannot be put down, because blocks do not exist at an angle. It stays
     * in the air and the operator levels it first, which is also what a rigger does. The
     * exception is {@code force}: when nothing is holding the rig any more, coming down
     * square is better than hanging there for ever.
     */
    public boolean land(ServerLevel level) {
        return land(level, false);
    }

    public boolean land(ServerLevel level, boolean force) {
        if (landed) {
            return true;
        }
        RigStructure snapshot = structure();
        if (snapshot == null) {
            landed = true;
            HoistFixtureCompat.release(rigId());
            discard();
            return true;
        }
        if (!force && !transform.flat()) {
            return false;
        }

        BlockPos target = origin().above((int) Math.round(travel()));
        if (!snapshot.fits(level, target)) {
            // Something moved in underneath while the rig was flying. Search upwards for
            // the nearest clear grid slot rather than overwriting whatever is there.
            BlockPos clear = null;
            for (int i = 1; i <= 8 && clear == null; i++) {
                if (snapshot.fits(level, target.above(i))) {
                    clear = target.above(i);
                }
            }
            if (clear == null) {
                return false;
            }
            target = clear;
        }

        // Unpatch the flown fixtures first: the real block entities register themselves as
        // they are placed, and two consumers on one DMX address is one too many.
        HoistFixtureCompat.release(rigId());

        snapshot.placeAt(level, target);
        landed = true;

        UUID id = rigId();
        if (id != null) {
            RigRegistry registry = RigRegistry.get(level);
            registry.reclaim(id, RigCollisionDetector.footprint(snapshot, target));
            registry.release(id);
        }
        dev.nano.ndidisplays.compat.sef.SefHoistCompat.onRigLanded(level, snapshot, target);
        discard();
        return true;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        if (level().isClientSide) {
            return;
        }
        if (landed) {
            discard();
            return;
        }
        ServerLevel server = (ServerLevel) level();

        // A rig only flies while a motor is holding it. Ownership is read from the
        // registry rather than remembered here, so a rig whose owner was mined follows
        // the promotion instead of landing on a stale reference.
        UUID id = rigId();
        RigRegistry.Rig rig = id == null ? null : RigRegistry.get(server).get(id);
        if (rig == null) {
            LOG.debug("Chain hoist: rig {} is not registered, landing it", id);
            land(server, true);
            return;
        }
        if (!server.isLoaded(rig.owner())) {
            // The motor's chunk is not loaded. Hold the load and wait — landing it into
            // a world we cannot see would be the one genuinely destructive option.
            return;
        }
        if (!(server.getBlockEntity(rig.owner()) instanceof ChainHoistBlockEntity motor)
                || !motor.owns(id)) {
            LOG.debug("Chain hoist: rig {} has no owning motor at {}, landing it",
                    id, rig.owner());
            land(server, true);
            return;
        }

        keepFixturesLive(server, id);
    }

    /**
     * Keeps any Theatrical lights on this load patched and pushes their head state to
     * clients, so a flown moving head goes on running its cue instead of freezing.
     *
     * Patching happens here rather than at capture so a rig that was airborne across a
     * restart comes back live too.
     */
    private void keepFixturesLive(ServerLevel server, UUID id) {
        RigStructure snapshot = structure;
        if (snapshot == null) {
            return;
        }
        HoistFixtureCompat.track(server, id, snapshot);
        CompoundTag live = HoistFixtureCompat.poll(id);
        if (live == null) {
            if (!entityData.get(FIXTURES).isEmpty()) {
                entityData.set(FIXTURES, new CompoundTag());
            }
            return;
        }
        // Only on change: a rig parked under a static look would otherwise put a packet on
        // the wire twenty times a second for every client watching it.
        if (!live.equals(entityData.get(FIXTURES))) {
            entityData.set(FIXTURES, live);
        }
    }

    @Override
    public boolean shouldBeSaved() {
        // The load lives in this entity. If it were not saved, unloading the chunk would
        // delete a truss and everything patched onto it.
        return !landed;
    }

    @Override
    public void remove(RemovalReason reason) {
        // Being unloaded is fine — the entity is saved with its chunk and picks up where
        // it left off. Being killed or discarded without landing would destroy the load,
        // so it gets put back first, square even if it was hanging at an angle.
        if (!landed && reason.shouldDestroy() && level() instanceof ServerLevel server
                && structure() != null && structure().size() > 0) {
            RigStructure snapshot = structure();
            HoistFixtureCompat.release(rigId());
            BlockPos target = origin().above((int) Math.round(travel()));
            snapshot.placeAt(server, target);
            landed = true;
            UUID id = rigId();
            if (id != null) {
                RigRegistry.get(server).release(id);
            }
        } else if (!level().isClientSide) {
            // The load is still ours, just out of sight. Drop the DMX proxies so they are
            // not left patched with nothing driving them; the next tick re-creates them.
            HoistFixtureCompat.release(rigId());
        }
        super.remove(reason);
    }

    // ------------------------------------------------------------------ plumbing

    @Override
    protected void defineSynchedData() {
        entityData.define(STRUCTURE, new CompoundTag());
        entityData.define(ORIGIN, BlockPos.ZERO);
        entityData.define(RIG_ID, Optional.empty());
        entityData.define(GRADIENT, new Vector3f());
        entityData.define(PIVOT, new Vector3f());
        entityData.define(FIXTURES, new CompoundTag());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STRUCTURE.equals(key)) {
            structureDirty = true;
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Structure")) {
            structure = RigStructure.load(level(), tag.getCompound("Structure"));
            entityData.set(STRUCTURE, structure.saveForClient());
        }
        entityData.set(ORIGIN, BlockPos.of(tag.getLong("Origin")));
        if (tag.hasUUID("RigId")) {
            entityData.set(RIG_ID, Optional.of(tag.getUUID("RigId")));
        }
        landed = tag.getBoolean("Landed");

        // A tilted rig comes back tilted. Travel rides on the saved position, so only the
        // slope and its pivot have to be restored by hand.
        transform = new RigTransform(travel(),
                tag.getDouble("GradX"), tag.getDouble("GradZ"),
                tag.getDouble("PivotX"), tag.getDouble("PivotY"), tag.getDouble("PivotZ"));
        entityData.set(GRADIENT,
                new Vector3f((float) transform.gradX(), 0.0F, (float) transform.gradZ()));
        entityData.set(PIVOT, new Vector3f((float) transform.pivotX(),
                (float) transform.pivotY(), (float) transform.pivotZ()));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (structure != null) {
            tag.put("Structure", structure.save());
        }
        tag.putLong("Origin", origin().asLong());
        UUID id = rigId();
        if (id != null) {
            tag.putUUID("RigId", id);
        }
        tag.putBoolean("Landed", landed);
        tag.putDouble("GradX", transform.gradX());
        tag.putDouble("GradZ", transform.gradZ());
        tag.putDouble("PivotX", transform.pivotX());
        tag.putDouble("PivotY", transform.pivotY());
        tag.putDouble("PivotZ", transform.pivotZ());
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
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean ignoreExplosion() {
        // A blast must not be able to delete a load that is only held as data.
        return true;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    protected AABB makeBoundingBox() {
        RigStructure snapshot = structure;
        if (snapshot == null) {
            return new AABB(getX(), getY(), getZ(), getX() + 1, getY() + 1, getZ() + 1);
        }
        AABB local = snapshot.localBounds().move(getX(), getY(), getZ());
        // A sloped load reaches outside the box its square offsets describe: the far end of
        // a long truss swings up and in. Half the span covers any angle this hoist allows.
        RigTransform current = level().isClientSide ? renderTransform() : transform;
        if (current.flat()) {
            return local;
        }
        double span = Math.max(local.getXsize(), local.getZsize()) * 0.5;
        return local.inflate(span);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        // A rig's hitbox is meaningless but its load can be thirty blocks across, so
        // culling has to test what is actually drawn.
        return getBoundingBox().inflate(1.0);
    }
}
