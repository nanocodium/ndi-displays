package dev.nano.ndidisplays.hoist;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.compat.theatrical.HoistFixtureCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * A flown load, lifted out of the world and held as data.
 *
 * This is the only copy of the load while it is in the air. The blocks are gone from the
 * level and live here, on the owning {@link dev.nano.ndidisplays.entity.MovingRigEntity},
 * until they are put back. That is what makes the hoist safe: there is never a moment where
 * both the world and the rig hold the same truss.
 *
 * <h3>Why full metadata</h3>
 * Each entry keeps the whole {@link BlockState} and, when the block has one, the block
 * entity's {@link BlockEntity#saveWithFullMetadata()} output. Not a hand-picked list of
 * fields, not {@code getUpdateTag()} — those are lossy, and a flown line array or a patched
 * lighting fixture that comes back down missing its configuration is worse than one that
 * never flew. Full metadata means a block this mod has never heard of survives the trip
 * with its inventory, its DMX patch and its mod-specific NBT intact.
 */
public final class RigStructure {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Placement flags: tell clients, but do not run neighbour or shape updates.
     *
     * Shape updates are the dangerous ones. They are what makes a torch pop off a wall or
     * makes Theatrical's hangable fixtures decide they have lost their support and spawn a
     * falling entity. A rig lands as a unit, so every block's neighbours appear in the same
     * tick and none of them should be asked to re-evaluate mid-placement.
     */
    private static final int PLACE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** One captured block: where it sits relative to the rig origin, its state, its NBT. */
    public record Entry(BlockPos offset, BlockState state, @Nullable CompoundTag blockEntity) {
    }

    private final List<Entry> entries;
    private final BlockPos origin;

    private RigStructure(BlockPos origin, List<Entry> entries) {
        this.origin = origin.immutable();
        this.entries = entries;
    }

    public BlockPos origin() {
        return origin;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    // ------------------------------------------------------------------ capture

    /**
     * Lifts {@code positions} out of the level into a snapshot, in one server tick.
     *
     * Order matters. Every block is read before any is removed, so a block entity that
     * inspects its neighbours while being torn down cannot see a half-demolished rig.
     * Containers are cleared only after their contents have been recorded, which is what
     * stops a flown speaker stack raining its own items onto the stage.
     */
    public static RigStructure capture(Level level, BlockPos origin, Collection<BlockPos> positions) {
        List<Entry> captured = new ArrayList<>(positions.size());

        // Deterministic order keeps the snapshot NBT stable between saves, which makes
        // desyncs and duplication bugs reproducible instead of intermittent.
        List<BlockPos> ordered = new ArrayList<>(positions);
        ordered.sort(Comparator.comparingLong(BlockPos::asLong));

        for (BlockPos pos : ordered) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            CompoundTag tag = be != null ? be.saveWithFullMetadata() : null;
            if (tag != null) {
                // Position is re-derived on the way down; carrying it would pin the block
                // entity to where it took off.
                tag.remove("x");
                tag.remove("y");
                tag.remove("z");
            }
            captured.add(new Entry(pos.subtract(origin), state, tag));
        }

        for (Entry entry : captured) {
            BlockPos pos = origin.offset(entry.offset());
            // Contents are already in the snapshot; clearing here stops onRemove dropping
            // a second copy as items.
            Clearable.tryClear(level.getBlockEntity(pos));
            level.removeBlockEntity(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        }

        return new RigStructure(origin, captured);
    }

    // ------------------------------------------------------------------ repose

    /**
     * Puts the load back down with its origin at {@code newOrigin}.
     *
     * Two passes: every state first, then every block entity. A block entity restored
     * before its neighbours exist can latch onto the wrong thing — an LED panel picking
     * its wall anchor, a speaker looking for the rest of its array — so nothing is loaded
     * until the whole structure is standing.
     *
     * @return true if every block was placed
     */
    public boolean placeAt(Level level, BlockPos newOrigin) {
        boolean complete = true;

        for (Entry entry : entries) {
            BlockPos pos = newOrigin.offset(entry.offset());
            if (!level.setBlock(pos, entry.state(), PLACE_FLAGS)) {
                complete = false;
            }
        }

        for (Entry entry : entries) {
            CompoundTag tag = entry.blockEntity();
            if (tag == null) {
                continue;
            }
            BlockPos pos = newOrigin.offset(entry.offset());
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                LOG.warn("Chain hoist: no block entity at {} for {}, its data is lost",
                        pos, entry.state());
                complete = false;
                continue;
            }
            String expected = String.valueOf(BlockEntityType.getKey(be.getType()));
            String stored = tag.getString("id");
            if (!stored.isEmpty() && !stored.equals(expected)) {
                // The state placed a different block entity than the one captured. Loading
                // the tag anyway would feed a speaker's NBT to a chest.
                LOG.warn("Chain hoist: block entity at {} is {} but the snapshot holds {};"
                        + " leaving it unconfigured", pos, expected, stored);
                complete = false;
                continue;
            }
            try {
                // Block entities are addressed by position, and a few read it back out of
                // their own tag. The snapshot is position-independent by design, so the
                // coordinates are written back here, for where the block actually landed.
                CompoundTag placed = tag.copy();
                placed.putInt("x", pos.getX());
                placed.putInt("y", pos.getY());
                placed.putInt("z", pos.getZ());
                be.load(placed);
                be.setChanged();
            } catch (RuntimeException e) {
                // A malformed or version-shifted tag must not take the whole rig with it:
                // the block is already standing, it just comes back unconfigured.
                LOG.warn("Chain hoist: could not restore block entity data at {}", pos, e);
                complete = false;
            }
        }

        // Now that the rig is whole, let it settle into the world: light, redstone and
        // supports all re-evaluate against the finished structure rather than a partial one.
        for (Entry entry : entries) {
            BlockPos pos = newOrigin.offset(entry.offset());
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            level.updateNeighborsAt(pos, state.getBlock());
        }

        return complete;
    }

    /** True when every block of the load could stand at {@code newOrigin} right now. */
    public boolean fits(Level level, BlockPos newOrigin) {
        for (Entry entry : entries) {
            BlockPos pos = newOrigin.offset(entry.offset());
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir() && !existing.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /** Local bounding box of the load, in blocks, relative to the origin. */
    public AABB localBounds() {
        if (entries.isEmpty()) {
            return new AABB(0, 0, 0, 1, 1, 1);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Entry entry : entries) {
            BlockPos o = entry.offset();
            minX = Math.min(minX, o.getX());
            minY = Math.min(minY, o.getY());
            minZ = Math.min(minZ, o.getZ());
            maxX = Math.max(maxX, o.getX());
            maxY = Math.max(maxY, o.getY());
            maxZ = Math.max(maxZ, o.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
    }

    // ------------------------------------------------------------------ persistence

    public CompoundTag save() {
        return write(true);
    }

    /**
     * Snapshot the client needs in order to draw the load.
     *
     * Ordinary baked models go across as states only — a chest's inventory is nobody's
     * business on the client. Blocks that hide their baked model also send their
     * block-entity tag, because without it the client has nothing to draw and the load
     * vanishes until it lands. That covers wrench-posed speakers and TESR cabinets, and it
     * covers lighting fixtures, whose head position and colour are drawn from the tag while
     * the rig flies.
     */
    public CompoundTag saveForClient() {
        return write(false);
    }

    /**
     * True when the baked block model will not show this block, so the client needs the
     * block entity (or a fallback state) to see it at all while the rig is flying.
     */
    public static boolean clientNeedsBlockEntity(BlockState state) {
        RenderShape shape = state.getRenderShape();
        if (shape == RenderShape.INVISIBLE || shape == RenderShape.ENTITYBLOCK_ANIMATED) {
            return true;
        }
        // Theatrical lights draw from the block entity even when their baked model is
        // visible — without the tag the client has no head pose or colour in flight.
        return HoistFixtureCompat.isFixture(state);
    }

    private CompoundTag write(boolean includeBlockEntities) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Origin", origin.asLong());
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag e = new CompoundTag();
            e.putLong("P", entry.offset().asLong());
            e.put("S", NbtUtils.writeBlockState(entry.state()));
            if (entry.blockEntity() != null
                    && (includeBlockEntities || clientNeedsBlockEntity(entry.state()))) {
                e.put("B", entry.blockEntity());
            }
            list.add(e);
        }
        tag.put("Blocks", list);
        return tag;
    }

    public static RigStructure load(Level level, CompoundTag tag) {
        BlockPos origin = BlockPos.of(tag.getLong("Origin"));
        ListTag list = tag.getList("Blocks", Tag.TAG_COMPOUND);
        List<Entry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            BlockState state = NbtUtils.readBlockState(
                    level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    e.getCompound("S"));
            entries.add(new Entry(
                    BlockPos.of(e.getLong("P")),
                    state,
                    e.contains("B") ? e.getCompound("B") : null));
        }
        return new RigStructure(origin, entries);
    }

    /** Empty snapshot, for a rig entity that has not been told what it carries yet. */
    public static RigStructure empty() {
        return new RigStructure(BlockPos.ZERO, List.of());
    }
}
