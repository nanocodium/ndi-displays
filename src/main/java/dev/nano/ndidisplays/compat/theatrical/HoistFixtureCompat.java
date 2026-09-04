package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.hoist.RigStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps Theatrical lighting fixtures alive while a chain hoist is flying them.
 *
 * A flown load is not in the world: its blocks are held as data on a
 * {@link dev.nano.ndidisplays.entity.MovingRigEntity}, which is what makes the hoist safe.
 * The cost is that a moving head on that truss stops being a DMX consumer the moment it
 * takes off, so a rig would go dark mid-cue and snap back to life on landing. That is the
 * one thing a lighting designer would never accept, so this class puts it back.
 *
 * <h3>How</h3>
 * On the server each fixture in the load gets a proxy block entity, registered with
 * Theatrical at the address the fixture took off from. It receives DMX exactly as it did on
 * the ground. Every tick the rig entity collects the proxies' head state — pan, tilt, focus,
 * intensity, colour — and syncs it to clients, where the renderer pushes it onto the ghost
 * fixtures it is already drawing. Theatrical's own renderer then draws body and beam at the
 * flying position, so a chase keeps running while the truss moves.
 *
 * <h3>Safety</h3>
 * This class never lets a Theatrical problem reach the hoist. Every entry point is guarded,
 * and if the integration is unavailable or throws, flying still works: the fixtures simply
 * hold the look they took off with, which is what the previous version did for everything.
 */
public final class HoistFixtureCompat {

    private static final Logger LOG = LogUtils.getLogger();

    /** Live head state per fixture: index into the snapshot, then seven channel values. */
    private static final String LIST = "L";
    private static final String INDEX = "i";
    private static final String VALUES = "v";

    private static volatile boolean broken;

    /**
     * Proxies per flying rig. Server-side only, and deliberately not persisted: a restart
     * rebuilds them from the snapshot on the rig entity's first tick.
     */
    private static final Map<UUID, List<Tracked>> TRACKED = new HashMap<>();

    private record Tracked(int index, BlockEntity proxy) {
    }

    private HoistFixtureCompat() {
    }

    private static boolean active() {
        return TheatricalCompat.LOADED && !broken;
    }

    private static void markBroken(Throwable e) {
        broken = true;
        LOG.warn("[ndidisplays] Theatrical fixtures cannot be kept live while flying on this"
                + " build; flown lights will hold the look they took off with", e);
    }

    /** True when this block is a Theatrical light, so its data has to reach the client. */
    public static boolean isFixture(BlockState state) {
        if (!active()) {
            return false;
        }
        try {
            return HoistFixtureHooks.isFixture(state);
        } catch (LinkageError e) {
            markBroken(e);
            return false;
        }
    }

    /**
     * Makes sure every fixture in a flying load is patched into its DMX network.
     *
     * Idempotent, and called every tick by the rig entity rather than once at capture, so a
     * rig that was in the air across a server restart comes back patched too.
     */
    public static void track(ServerLevel level, UUID rigId, RigStructure structure) {
        if (!active() || rigId == null) {
            return;
        }
        synchronized (TRACKED) {
            if (TRACKED.containsKey(rigId)) {
                return;
            }
            List<Tracked> tracked = new ArrayList<>();
            try {
                List<RigStructure.Entry> entries = structure.entries();
                for (int i = 0; i < entries.size(); i++) {
                    RigStructure.Entry entry = entries.get(i);
                    if (entry.blockEntity() == null
                            || !HoistFixtureHooks.isFixture(entry.state())) {
                        continue;
                    }
                    BlockPos pos = structure.origin().offset(entry.offset());
                    BlockEntity proxy = HoistFixtureHooks.createProxy(
                            level, pos, entry.state(), entry.blockEntity());
                    if (proxy != null) {
                        tracked.add(new Tracked(i, proxy));
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                releaseAll(tracked);
                markBroken(e);
                return;
            }
            // Recorded even when empty: that is what stops this running the whole snapshot
            // again every tick for a load with no lights on it.
            TRACKED.put(rigId, tracked);
        }
    }

    /**
     * Current head state of a rig's fixtures, or null when there is nothing to send.
     *
     * @return a tag for {@link #applyLive}, or null when this rig has no live fixtures
     */
    @Nullable
    public static CompoundTag poll(UUID rigId) {
        if (!active() || rigId == null) {
            return null;
        }
        List<Tracked> tracked;
        synchronized (TRACKED) {
            tracked = TRACKED.get(rigId);
        }
        if (tracked == null || tracked.isEmpty()) {
            return null;
        }
        try {
            ListTag list = new ListTag();
            for (Tracked entry : tracked) {
                int[] values = HoistFixtureHooks.readLive(entry.proxy());
                if (values == null) {
                    continue;
                }
                CompoundTag item = new CompoundTag();
                item.putInt(INDEX, entry.index());
                item.putIntArray(VALUES, values);
                list.add(item);
            }
            if (list.isEmpty()) {
                return null;
            }
            CompoundTag tag = new CompoundTag();
            tag.put(LIST, list);
            return tag;
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
            return null;
        }
    }

    /**
     * Hands the fixtures back to the world. Called before the load is placed, so the real
     * block entities register themselves cleanly rather than fighting a stale proxy for the
     * same DMX address.
     */
    public static void release(UUID rigId) {
        if (rigId == null) {
            return;
        }
        List<Tracked> tracked;
        synchronized (TRACKED) {
            tracked = TRACKED.remove(rigId);
        }
        if (tracked != null) {
            releaseAll(tracked);
        }
    }

    private static void releaseAll(List<Tracked> tracked) {
        for (Tracked entry : tracked) {
            try {
                HoistFixtureHooks.releaseProxy(entry.proxy());
            } catch (RuntimeException | LinkageError e) {
                // Leaving one consumer registered is untidy; failing to land the load
                // because of it would be a great deal worse.
                LOG.debug("[ndidisplays] could not unpatch a flown fixture", e);
            }
        }
    }

    // ------------------------------------------------------------------ client

    /**
     * Pushes the synced head state onto the ghost fixtures the rig renderer is drawing.
     *
     * @param ghosts   one entry per snapshot block, null where the block has no ghost
     * @param captured the snapshot, for the fixture NBT the live values are merged into
     */
    public static void applyLive(CompoundTag live, List<BlockEntity> ghosts,
                                 RigStructure captured) {
        if (!active() || live.isEmpty()) {
            return;
        }
        try {
            ListTag list = live.getList(LIST, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompound(i);
                int index = item.getInt(INDEX);
                if (index < 0 || index >= ghosts.size() || index >= captured.entries().size()) {
                    continue;
                }
                BlockEntity ghost = ghosts.get(index);
                CompoundTag tag = captured.entries().get(index).blockEntity();
                if (ghost == null || tag == null) {
                    continue;
                }
                int[] values = item.getIntArray(VALUES);
                if (values.length < HoistFixtureHooks.VALUE_COUNT) {
                    continue;
                }
                HoistFixtureHooks.applyLive(ghost, tag, values);
            }
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }
}
