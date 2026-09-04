package dev.nano.ndidisplays.hoist;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Asks whether the load may occupy the space it is heading for.
 *
 * The test runs before the move is committed, so a rig that meets something stops against
 * it instead of grinding through it. Nothing is ever broken to make room — an obstruction
 * is the operator's problem to clear, which is exactly how a real motor behaves when the
 * truss finds the ceiling.
 *
 * <h3>Tilted loads</h3>
 * A block held at an angle overlaps more than one grid cell, but it is still one block and
 * it still has to come down on one. The cell it is tested against is the one its centre
 * sits in, which is the same cell it would land in. A truss can therefore graze a corner
 * while sloped, which is the price of letting it slope at all; it cannot pass through
 * anything, because the centre has to cross the obstruction to get to the other side.
 */
public final class RigCollisionDetector {

    private RigCollisionDetector() {
    }

    /**
     * Checks the whole load against the space {@code transform} would put it in.
     *
     * @param occupied where the load is standing now — it is vacating these in the same
     *                 step, so they are not obstructions
     * @return true when every block of the load has somewhere to be
     */
    public static boolean canOccupy(ServerLevel level, Set<BlockPos> wanted,
                                    Set<BlockPos> occupied, UUID rigId) {
        for (BlockPos pos : wanted) {
            if (occupied.contains(pos)) {
                continue;
            }
            if (!level.isLoaded(pos)) {
                // Flying into terrain that is not there yet would mean placing the load
                // blind. Stop at the edge of the loaded world instead.
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
        }

        // Another rig may be flying through the same air even though the blocks are gone
        // from the level. Two loads cannot share a landing site.
        return !RigRegistry.get(level).overlaps(rigId, wanted);
    }

    /** Where the load stands for a given transform, one cell per block. */
    public static Set<BlockPos> footprint(RigStructure structure, BlockPos origin,
                                          RigTransform transform) {
        Set<BlockPos> out = new LinkedHashSet<>(structure.size());
        for (RigStructure.Entry entry : structure.entries()) {
            out.add(transform.cellOf(origin, entry.offset()));
        }
        return out;
    }

    /** Where the load stands for a plain vertical offset, used when landing. */
    public static Set<BlockPos> footprint(RigStructure structure, BlockPos origin) {
        Set<BlockPos> out = new LinkedHashSet<>(structure.size());
        for (RigStructure.Entry entry : structure.entries()) {
            out.add(origin.offset(entry.offset()));
        }
        return out;
    }
}
