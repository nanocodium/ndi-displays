package dev.nano.ndidisplays.hoist;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out what is hanging on the hook.
 *
 * The hoist has no idea what a truss is, and deliberately so: a flown load is whatever
 * connected island of blocks the hook is attached to, whether that is SEF truss, a
 * Theatrical lighting bar, a stack of line-array cabinets, LED tiles, or somebody's
 * hand-built scenery out of oak planks.
 *
 * <h3>The one rule that makes this safe</h3>
 * The island has to be <em>isolated</em> from walls and neighbouring buildings. Resting
 * on the floor (or parked against a ceiling) is allowed — that is how a set piece sits
 * on the deck between cues. A flood fill that runs out of blocks has found a flown
 * structure. A flood fill that stops against a wall it is welded to has found a building
 * — and that is a fault, not a smaller load. There is no "take the first 256 blocks"
 * path through this class, because that is precisely how you would saw a venue in half.
 *
 * In practice: a PA hang, a truss grid, or scenery sitting on the grass flies; a hoist
 * stuck to the side of a house refuses to pick up the house.
 */
public final class StructureScanner {

    /** SEF's decorative chain, matched by id so this class never imports SEF. */
    private static final ResourceLocation SEF_CHAIN = new ResourceLocation("sef", "chain");

    private StructureScanner() {
    }

    /**
     * Floods out from the block under {@code hookPos} and returns the whole load, or the
     * reason there isn't one.
     *
     * @param initiator the hoist that asked, which is always a motor of the result
     */
    public static ScanResult scan(Level level, BlockPos hookPos, BlockPos initiator) {
        if (!level.isLoaded(hookPos)) {
            return ScanResult.failed(ScanResult.Failure.UNLOADED);
        }
        BlockState start = level.getBlockState(hookPos);
        if (start.isAir() || start.canBeReplaced()) {
            return ScanResult.failed(ScanResult.Failure.EMPTY);
        }
        if (!liftable(level, hookPos, start)) {
            return ScanResult.failed(ScanResult.Failure.NOT_LIFTABLE);
        }

        int maxBlocks = HoistConfig.maxBlocks();
        int maxX = HoistConfig.MAX_SIZE_X.get();
        int maxY = HoistConfig.MAX_SIZE_Y.get();
        int maxZ = HoistConfig.MAX_SIZE_Z.get();

        Set<BlockPos> load = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        load.add(hookPos.immutable());
        queue.add(hookPos.immutable());

        int minX = hookPos.getX(), minY = hookPos.getY(), minZ = hookPos.getZ();
        int hiX = minX, hiY = minY, hiZ = minZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (load.contains(next)) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    return ScanResult.failed(ScanResult.Failure.UNLOADED);
                }
                BlockState state = level.getBlockState(next);
                if (state.isAir() || state.canBeReplaced()) {
                    continue;
                }
                if (!liftable(level, next, state)) {
                    // Terrain, machinery or a chain. Not part of the load — whether that
                    // is acceptable is settled by the isolation test below.
                    continue;
                }

                // Growing past a cap is a fault. Trimming the load to fit would hand back
                // a structure sliced at an arbitrary boundary, which is never what anyone
                // meant to fly.
                if (load.size() + 1 > maxBlocks) {
                    return ScanResult.failed(ScanResult.Failure.TOO_MANY_BLOCKS);
                }
                if (Math.max(hiX, next.getX()) - Math.min(minX, next.getX()) + 1 > maxX
                        || Math.max(hiY, next.getY()) - Math.min(minY, next.getY()) + 1 > maxY
                        || Math.max(hiZ, next.getZ()) - Math.min(minZ, next.getZ()) + 1 > maxZ) {
                    return ScanResult.failed(ScanResult.Failure.TOO_LARGE);
                }
                minX = Math.min(minX, next.getX());
                minY = Math.min(minY, next.getY());
                minZ = Math.min(minZ, next.getZ());
                hiX = Math.max(hiX, next.getX());
                hiY = Math.max(hiY, next.getY());
                hiZ = Math.max(hiZ, next.getZ());

                BlockPos immutable = next.immutable();
                load.add(immutable);
                queue.add(immutable);
            }
        }

        Set<BlockPos> motors = findMotors(level, load, initiator);
        if (motors.size() > HoistConfig.maxMotors()) {
            return ScanResult.failed(ScanResult.Failure.TOO_MANY_MOTORS);
        }
        if (!isolated(level, load, motors)) {
            return ScanResult.failed(ScanResult.Failure.NOT_ISOLATED);
        }
        return ScanResult.success(load, motors);
    }

    /**
     * Whether a block can travel at all.
     *
     * Everything is liftable by default; that is what lets an unknown modded block fly.
     * The exceptions are terrain (the world is not cargo), tagged immovables, and the
     * hoists themselves — a motor lifts, it does not get lifted.
     */
    private static boolean liftable(Level level, BlockPos pos, BlockState state) {
        if (state.is(HoistTags.WORLD) || state.is(HoistTags.IMMOVABLE)) {
            return false;
        }
        if (isRigHardware(state)) {
            return false;
        }
        if (isChain(state)) {
            return false;
        }
        // Anything indestructible is world furniture by definition, whatever it is called.
        return state.getDestroySpeed(level, pos) >= 0;
    }

    /** SEF's chain block, if SEF is installed: part of a rig's rigging, not its load. */
    private static boolean isChain(BlockState state) {
        return SEF_CHAIN.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    /**
     * Machinery that lifts things rather than being lifted: this hoist and the LED and
     * camera winches.
     *
     * All three are bolted to a grid and already fly their own payload. Capturing one
     * would put a winch that is flying tiles inside a rig that is flying the winch, so
     * they are neither cargo nor a reason to call the load welded to the world — they are
     * simply the hardware overhead, same as a neighbouring hoist.
     */
    private static boolean isRigHardware(BlockState state) {
        return state.getBlock() == NdiDisplays.CHAIN_HOIST.get()
                || state.getBlock() == NdiDisplays.KINETIC_WINCH.get();
    }

    /**
     * Confirms the load is not welded to a wall or a neighbouring building.
     *
     * Sitting on the floor or parking under a ceiling is not a weld: those are the two
     * places a flown piece actually stops. A single horizontal contact with anything that
     * is not the load — a bolt into a wall, a neighbouring tower — is, and the hoist
     * faults rather than tearing it loose.
     */
    private static boolean isolated(Level level, Set<BlockPos> load, Set<BlockPos> motors) {
        for (BlockPos pos : load) {
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (load.contains(next) || motors.contains(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (state.isAir() || state.canBeReplaced() || isChain(state)) {
                    continue;
                }
                if (isRigHardware(state)) {
                    // A hoist that is not one of ours is somebody else's rig sharing a
                    // face; a winch is grid hardware. Neither welds the load to the world.
                    continue;
                }
                // Terrain or an immovable under / over the load is the deck or the grid,
                // not a weld. The same block on a side face is a wall.
                if (dir.getAxis() == Direction.Axis.Y && !liftable(level, next, state)) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Finds every hoist whose chain reaches this load — the multi-point case, where a
     * long truss hangs on two or four motors.
     *
     * Getting this wrong is worse than it sounds. A motor left out of the rig does not
     * merely miss out: it stays unattached, so it holds no load, draws no chain, and the
     * operator sees one chain on a four-point hang. So the search is deliberately generous
     * about what counts as carrying, and asks four questions in turn.
     *
     * <ol>
     *   <li>Straight above the top of a column, with only air or chain in between — the
     *       textbook case, a chain dropping onto the truss.</li>
     *   <li>Above a column but one block to the side, which is what a motor bolted to a
     *       roof beam next to the truss line actually looks like.</li>
     *   <li>Sharing a face with the load, for a motor built into the grid itself.</li>
     *   <li>Patched into the same group as the motor that was asked, and hanging over the
     *       load. The operator has already said these run together; refusing to lift
     *       together would just leave three motors idle.</li>
     * </ol>
     */
    private static Set<BlockPos> findMotors(Level level, Set<BlockPos> load, BlockPos initiator) {
        Set<BlockPos> motors = new LinkedHashSet<>();
        motors.add(initiator.immutable());

        int reach = Math.round(HoistConfig.maxChainLength()) + 1;
        // Only the top of each column can be under a chain, so the ray casts are few.
        List<BlockPos> tops = new ArrayList<>();
        for (BlockPos pos : load) {
            if (!load.contains(pos.above())) {
                tops.add(pos);
            }
        }

        Set<BlockPos> seen = new HashSet<>();
        seen.add(initiator.immutable());
        for (BlockPos top : tops) {
            castForMotor(level, top, reach, load, seen, motors);
            // One block either side: a roof beam is rarely dead centre over the truss.
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos beside = top.relative(dir);
                if (!load.contains(beside)) {
                    castForMotor(level, beside, reach, load, seen, motors);
                }
            }
        }

        // A motor sitting in the truss (same height, sharing a face) is carrying it
        // even though no chain drops onto a top face. That is the usual first build:
        // the hoist is placed in the grid, not clamped to a roof above it.
        for (BlockPos pos : load) {
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (!level.isLoaded(next)) {
                    continue;
                }
                if (isHoist(level.getBlockState(next)) && seen.add(next.immutable())) {
                    motors.add(next.immutable());
                }
            }
        }

        addGroupMotors(level, load, initiator, reach, seen, motors);
        return motors;
    }

    /**
     * Walks up from one column looking for a motor, stopping at the first thing that is
     * neither air, chain, nor part of this load. Load blocks are passed through: a motor
     * over a three-high tower is still over it.
     */
    private static void castForMotor(Level level, BlockPos from, int reach, Set<BlockPos> load,
                                     Set<BlockPos> seen, Set<BlockPos> motors) {
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int i = 0; i < reach; i++) {
            cursor.move(Direction.UP);
            if (!level.isLoaded(cursor)) {
                return;
            }
            if (load.contains(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (isHoist(state)) {
                BlockPos found = cursor.immutable();
                if (seen.add(found)) {
                    motors.add(found);
                }
                return;
            }
            if (state.isAir() || state.canBeReplaced() || isChain(state)) {
                continue;
            }
            // Anything solid in the way means the chain does not run here.
            return;
        }
    }

    /**
     * Adds the motors the operator has already grouped with this one, when they hang over
     * the load at a plausible chain length.
     *
     * This is the escape hatch for rigging the geometry cannot see: a motor on a beam two
     * blocks off the truss line, or one whose shaft is interrupted by the roof it is bolted
     * through. Naming a group is an explicit statement that these motors work as a set, so
     * it is taken at face value — bounded by the load's own footprint and the longest chain
     * a hoist can pay out, so a group name can never reach across a venue.
     */
    private static void addGroupMotors(Level level, Set<BlockPos> load, BlockPos initiator,
                                       int reach, Set<BlockPos> seen, Set<BlockPos> motors) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (!(server.getBlockEntity(initiator) instanceof ChainHoistBlockEntity be)) {
            return;
        }
        String group = be.getGroup();
        if (group.isEmpty()) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int top = Integer.MIN_VALUE;
        for (BlockPos pos : load) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            top = Math.max(top, pos.getY());
        }

        for (BlockPos member : HoistGroups.get(server).members(group)) {
            if (seen.contains(member) || !server.isLoaded(member)) {
                continue;
            }
            if (!isHoist(server.getBlockState(member))) {
                continue;
            }
            // Over the load's footprint (a block of slack for beams just outside it) and
            // high enough to be holding it, but within chain reach.
            if (member.getX() < minX - 1 || member.getX() > maxX + 1
                    || member.getZ() < minZ - 1 || member.getZ() > maxZ + 1) {
                continue;
            }
            if (member.getY() <= top || member.getY() > top + reach) {
                continue;
            }
            seen.add(member.immutable());
            motors.add(member.immutable());
        }
    }

    private static boolean isHoist(BlockState state) {
        return state.getBlock() == NdiDisplays.CHAIN_HOIST.get();
    }

    /**
     * How good a candidate a block of the load is for a motor's hook to grab.
     *
     * Lower is better, and the ordering is the one a rigger would read off the drawing:
     * the column directly under the motor first, then a column beside it, and a block the
     * chain would have to climb to reach only if there is nothing else at all. Shared with
     * {@link dev.nano.ndidisplays.block.ChainHoistBlockEntity} so a motor picks the same
     * pick point whether it is starting a lift or joining one in progress.
     */
    public static double anchorScore(BlockPos motor, int x, int y, int z) {
        int horizontal = Math.abs(x - motor.getX()) + Math.abs(z - motor.getZ());
        int drop = motor.getY() - y;
        return horizontal * 64.0 + (drop >= 0 ? drop : 4096 - drop);
    }

    /**
     * Finds what the hook should pick up.
     *
     * Prefers the first liftable block under the motor (a chain dropping onto a truss).
     * If the motor is sitting in the grid itself — truss on its sides, grass far below —
     * the chain would otherwise latch onto the floor and refuse. In that case the
     * neighbouring truss is the load.
     */
    public static BlockPos findHookTarget(Level level, BlockPos motor, float maxChain) {
        BlockPos below = firstSolidBelow(level, motor, maxChain);
        if (below != null && liftable(level, below, level.getBlockState(below))) {
            return below;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = motor.relative(dir);
            if (!level.isLoaded(side)) {
                continue;
            }
            BlockState state = level.getBlockState(side);
            if (!state.isAir() && !state.canBeReplaced() && liftable(level, side, state)) {
                return side.immutable();
            }
        }
        return below;
    }

    @Nullable
    private static BlockPos firstSolidBelow(Level level, BlockPos motor, float maxChain) {
        int limit = Math.max(1, Math.round(maxChain));
        BlockPos.MutableBlockPos cursor = motor.mutable();
        for (int i = 1; i <= limit; i++) {
            cursor.move(Direction.DOWN);
            if (!level.isLoaded(cursor)) {
                return null;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.canBeReplaced() || isChain(state)) {
                continue;
            }
            return cursor.immutable();
        }
        return null;
    }

    /** Highest block of the load, used to keep the chain drawn to the top of the truss. */
    public static BlockPos highest(Set<BlockPos> load) {
        BlockPos best = null;
        for (BlockPos pos : load) {
            if (best == null || pos.getY() > best.getY()) {
                best = pos;
            }
        }
        return best;
    }
}
