package dev.nano.ndidisplays.hoist;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The book of what is currently in the air, one per level.
 *
 * A flown load exists exactly once, and this is what enforces it. Before any hoist may
 * lift a structure it has to claim it here; the claim is keyed by the blocks themselves,
 * so two motors racing to pick up the same truss on the same tick cannot both win. The
 * loser does not get its own copy of the truss — it joins the winner's rig as a follower.
 *
 * That single rule is what keeps the hoist from being a block duplicator. Everything
 * else (chunk unloads, server restarts, a motor being mined mid-flight) is handled by
 * asking this registry who actually owns the load.
 */
public class RigRegistry extends SavedData {

    private static final String NAME = "ndidisplays_rigs";

    /** One flown structure: its owning motor, its followers, and the ground it claims. */
    public static final class Rig {
        private final UUID rigId;
        private BlockPos owner;
        private final Set<BlockPos> motors = new LinkedHashSet<>();
        private final Set<BlockPos> claimed = new HashSet<>();
        private int entityId = -1;

        Rig(UUID rigId, BlockPos owner, Collection<BlockPos> motors, Collection<BlockPos> claimed) {
            this.rigId = rigId;
            this.owner = owner.immutable();
            for (BlockPos pos : motors) {
                this.motors.add(pos.immutable());
            }
            for (BlockPos pos : claimed) {
                this.claimed.add(pos.immutable());
            }
        }

        public UUID rigId() {
            return rigId;
        }

        public BlockPos owner() {
            return owner;
        }

        public Set<BlockPos> motors() {
            return motors;
        }

        public Set<BlockPos> claimed() {
            return claimed;
        }

        public int entityId() {
            return entityId;
        }

        public void setEntityId(int id) {
            this.entityId = id;
        }
    }

    private final Map<UUID, Rig> rigs = new HashMap<>();
    /** Reverse index from every claimed block to its rig, so overlap tests are O(1). */
    private final Map<BlockPos, UUID> claims = new HashMap<>();

    public static RigRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RigRegistry::load, RigRegistry::new, NAME);
    }

    /**
     * Picks the motor that owns a rig: the lowest packed position of the set.
     *
     * The rule only has to be deterministic and agreed on by everyone, so that two motors
     * scanning the same truss independently elect the same owner and the second one knows
     * to stand down.
     */
    public static BlockPos electOwner(Collection<BlockPos> motors) {
        BlockPos best = null;
        for (BlockPos pos : motors) {
            if (best == null || pos.asLong() < best.asLong()) {
                best = pos;
            }
        }
        return best == null ? null : best.immutable();
    }

    /**
     * Reserves a load for one rig.
     *
     * @return the new rig, or null when any of those blocks is already flying — in which
     *         case the caller must attach to {@link #rigAt} instead of lifting again
     */
    @Nullable
    public Rig tryClaim(UUID rigId, Collection<BlockPos> motors, Collection<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            if (claims.containsKey(pos)) {
                return null;
            }
        }
        BlockPos owner = electOwner(motors);
        if (owner == null) {
            return null;
        }
        Rig rig = new Rig(rigId, owner, motors, blocks);
        rigs.put(rigId, rig);
        for (BlockPos pos : rig.claimed()) {
            claims.put(pos, rigId);
        }
        setDirty();
        return rig;
    }

    @Nullable
    public Rig get(UUID rigId) {
        return rigId == null ? null : rigs.get(rigId);
    }

    /**
     * Adds a motor to a rig that is already flying, for a hoist that has caught up with a
     * load somebody else lifted. Kept here rather than mutating {@link Rig#motors()} from
     * outside so the change is actually written to disk.
     */
    public void addMotor(UUID rigId, BlockPos motor) {
        Rig rig = rigs.get(rigId);
        if (rig == null || !rig.motors().add(motor.immutable())) {
            return;
        }
        setDirty();
    }

    /** The rig currently flying the block at {@code pos}, if any. */
    @Nullable
    public Rig rigAt(BlockPos pos) {
        UUID id = claims.get(pos);
        return id == null ? null : rigs.get(id);
    }

    /** True when any of {@code blocks} is already spoken for by a different rig. */
    public boolean overlaps(UUID exclude, Collection<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            UUID id = claims.get(pos);
            if (id != null && !id.equals(exclude)) {
                return true;
            }
        }
        return false;
    }

    /** Moves a rig's claim to where the load now is, after it has travelled. */
    public void reclaim(UUID rigId, Collection<BlockPos> blocks) {
        Rig rig = rigs.get(rigId);
        if (rig == null) {
            return;
        }
        for (BlockPos pos : rig.claimed()) {
            claims.remove(pos);
        }
        rig.claimed().clear();
        for (BlockPos pos : blocks) {
            BlockPos immutable = pos.immutable();
            rig.claimed().add(immutable);
            claims.put(immutable, rigId);
        }
        setDirty();
    }

    /**
     * Hands a rig to a different motor, after the owner has been mined out from under it.
     *
     * @return the new owner, or null when the last motor is gone and the load has to land
     */
    @Nullable
    public BlockPos promote(UUID rigId, BlockPos removed) {
        Rig rig = rigs.get(rigId);
        if (rig == null) {
            return null;
        }
        rig.motors().remove(removed);
        BlockPos next = electOwner(rig.motors());
        if (next == null) {
            return null;
        }
        rig.owner = next;
        setDirty();
        return next;
    }

    public void release(UUID rigId) {
        Rig rig = rigs.remove(rigId);
        if (rig == null) {
            return;
        }
        for (BlockPos pos : rig.claimed()) {
            claims.remove(pos);
        }
        setDirty();
    }

    public Collection<Rig> all() {
        return rigs.values();
    }

    // ------------------------------------------------------------------ persistence

    public RigRegistry() {
    }

    public static RigRegistry load(CompoundTag tag) {
        RigRegistry registry = new RigRegistry();
        ListTag list = tag.getList("Rigs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID rigId = entry.getUUID("Id");
            BlockPos owner = BlockPos.of(entry.getLong("Owner"));
            List<BlockPos> motors = readPositions(entry.getLongArray("Motors"));
            List<BlockPos> claimed = readPositions(entry.getLongArray("Claimed"));
            Rig rig = new Rig(rigId, owner, motors, claimed);
            registry.rigs.put(rigId, rig);
            for (BlockPos pos : rig.claimed()) {
                registry.claims.put(pos, rigId);
            }
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Rig rig : rigs.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", rig.rigId());
            entry.putLong("Owner", rig.owner().asLong());
            entry.putLongArray("Motors", writePositions(rig.motors()));
            entry.putLongArray("Claimed", writePositions(rig.claimed()));
            list.add(entry);
        }
        tag.put("Rigs", list);
        return tag;
    }

    private static List<BlockPos> readPositions(long[] packed) {
        List<BlockPos> out = new ArrayList<>(packed.length);
        for (long value : packed) {
            out.add(BlockPos.of(value));
        }
        return out;
    }

    private static long[] writePositions(Collection<BlockPos> positions) {
        long[] out = new long[positions.size()];
        int i = 0;
        for (BlockPos pos : positions) {
            out[i++] = pos.asLong();
        }
        return out;
    }
}
