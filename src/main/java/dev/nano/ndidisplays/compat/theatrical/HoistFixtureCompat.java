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

    /** Live state per fixture: index into the snapshot, then the proxy's full saved tag. */
    private static final String LIST = "L";
    private static final String INDEX = "i";
    private static final String STATE = "t";

    private static volatile boolean broken;

    /**
     * {@code -Dndidisplays.debugFlownFixtures=true}: log every change to a flown fixture's
     * head state, per frame on the client and per tick on the server, so a one-tick glitch
     * can be pinned to the side and the value that produced it.
     */
    public static final boolean DEBUG = Boolean.getBoolean("ndidisplays.debugFlownFixtures");

    public static void debug(String message, Object... args) {
        if (DEBUG) {
            LOG.info("[ndidisplays] flown-fixture " + message, args);
        }
    }

    /** Head-state summary of a ghost or proxy, or "" when the integration is off. */
    public static String describe(@Nullable BlockEntity be) {
        if (!active() || be == null) {
            return "";
        }
        try {
            return HoistFixtureHooks.describe(be);
        } catch (RuntimeException | LinkageError e) {
            return "describe-failed";
        }
    }

    /** Summary of every fixture in a synced live tag, one line per fixture. */
    public static String describe(CompoundTag live) {
        if (!active() || live.isEmpty()) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            ListTag list = live.getList(LIST, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompound(i);
                sb.append(" #").append(item.getInt(INDEX)).append(' ')
                        .append(HoistFixtureHooks.describe(item.getCompound(STATE)));
            }
            return sb.toString();
        } catch (RuntimeException | LinkageError e) {
            return "describe-failed";
        }
    }

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
                CompoundTag state = HoistFixtureHooks.readLive(entry.proxy());
                if (state == null) {
                    continue;
                }
                CompoundTag item = new CompoundTag();
                item.putInt(INDEX, entry.index());
                item.put(STATE, state);
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
     * Re-patches every Theatrical consumer in a load that has just been placed.
     *
     * {@link RigStructure#placeAt} loads NBT after {@code setLevel}, so fixtures, screens
     * and winches come back with the right address in memory and nobody listening. Same
     * bug Create hits on disassembly; they re-register from {@code read}, we do it here
     * so this Theatrical build does not have to change.
     */
    public static void reattachLanded(net.minecraft.world.level.Level level,
                                      RigStructure structure, BlockPos newOrigin) {
        if (!active() || level == null || level.isClientSide || structure == null) {
            return;
        }
        try {
            for (RigStructure.Entry entry : structure.entries()) {
                BlockEntity be = level.getBlockEntity(newOrigin.offset(entry.offset()));
                if (be == null) {
                    continue;
                }
                HoistFixtureHooks.reattach(be);
                if (be instanceof dev.nano.ndidisplays.block.DmxScreen screen) {
                    TheatricalCompat.registerScreen(screen);
                }
                if (be instanceof dev.nano.ndidisplays.block.KineticWinchBlockEntity winch) {
                    TheatricalCompat.register(winch);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }

    /**
     * Writes each proxy's current state back into the snapshot the load will be placed
     * from, so a landed fixture keeps the look it had in the air rather than the one it
     * took off with. Without this every set-down snapped pan, tilt, gobo and colour back
     * to the take-off cue until the desk's next frame arrived. Call before {@link #release}.
     */
    public static void freshenSnapshot(UUID rigId, RigStructure structure) {
        if (!active() || rigId == null || structure == null) {
            return;
        }
        List<Tracked> tracked;
        synchronized (TRACKED) {
            tracked = TRACKED.get(rigId);
        }
        if (tracked == null) {
            return;
        }
        try {
            List<RigStructure.Entry> entries = structure.entries();
            for (Tracked entry : tracked) {
                if (entry.index() >= entries.size()) {
                    continue;
                }
                CompoundTag target = entries.get(entry.index()).blockEntity();
                CompoundTag state = HoistFixtureHooks.readLive(entry.proxy());
                if (target == null || state == null) {
                    continue;
                }
                // Overlay rather than replace: the snapshot's id and position keys stay.
                for (String key : state.getAllKeys()) {
                    Tag value = state.get(key);
                    if (value != null) {
                        target.put(key, value.copy());
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
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

    /** Ends the ghosts' head sweeps for a tick that brought no new values. */
    public static void settle(List<BlockEntity> ghosts) {
        if (!active()) {
            return;
        }
        try {
            for (BlockEntity ghost : ghosts) {
                if (ghost != null) {
                    HoistFixtureHooks.settle(ghost);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }

    /** True for an Extra Lights fixture whose mount transform can carry a sub-block offset. */
    public static boolean isMountable(@Nullable BlockEntity ghost) {
        if (ghost == null || !active()) {
            return false;
        }
        try {
            return HoistMountHooks.isMountable(ghost);
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
            return false;
        }
    }

    /**
     * Puts {@code delta}, the ghost's displacement from its block cell this frame, into an
     * Extra Lights fixture's mount offset, so body, quads and the shared raymarched volume
     * all draw it there. The caller poses the ghost at the cell itself.
     */
    public static void mountAt(BlockEntity ghost, net.minecraft.world.phys.Vec3 delta) {
        if (ghost == null || !active()) {
            return;
        }
        try {
            HoistMountHooks.mountAt(ghost, delta);
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }

    /**
     * Snapshot of Theatrical's beam queue before a ghost fixture is drawn, for
     * {@link #shiftBeamsSince}. Negative when beams cannot be re-anchored on this build.
     */
    public static int markBeams() {
        if (!active()) {
            return -1;
        }
        try {
            return HoistBeamHooks.available() ? HoistBeamHooks.mark() : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            markBroken(e);
            return -1;
        }
    }

    /**
     * Moves every beam a ghost fixture just queued by {@code delta}: the distance from the
     * whole-block cell the ghost is addressed at to where its body was actually drawn.
     */
    public static void shiftBeamsSince(int mark, net.minecraft.world.phys.Vec3 delta) {
        if (mark < 0 || !active() || delta.lengthSqr() < 1.0e-8) {
            return;
        }
        try {
            HoistBeamHooks.shiftSince(mark, delta);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }

    /**
     * Points a ghost fixture's beam at the first surface from where its body is drawn this
     * frame, so the beam ends on the floor rather than a whole-block figure away from it.
     */
    public static void aimBeam(BlockEntity ghost, net.minecraft.world.phys.Vec3 drawnCentre) {
        if (ghost == null || !active()) {
            return;
        }
        try {
            HoistBeamHooks.aimBeam(ghost, drawnCentre);
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }

    /**
     * Pushes the synced head state onto the ghost fixtures the rig renderer is drawing.
     *
     * @param ghosts   one entry per snapshot block, null where the block has no ghost
     * @param captured the snapshot the ghosts were built from, for bounds
     */
    public static void applyLive(CompoundTag live, List<BlockEntity> ghosts,
                                 RigStructure captured, boolean interpolate) {
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
                if (ghost == null || !item.contains(STATE, Tag.TAG_COMPOUND)) {
                    continue;
                }
                HoistFixtureHooks.applyLive(ghost, item.getCompound(STATE), interpolate);
            }
        } catch (RuntimeException | LinkageError e) {
            markBroken(e);
        }
    }
}
