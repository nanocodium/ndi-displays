package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A 90° corner LED cabinet: a quarter-cylinder screen of radius one block, wrapping a wall
 * around a corner. Convex ({@code CONVEX=true}) wraps an outside corner; the inner variant
 * lines an inside corner. Placed with sneak for the inner form.
 *
 * The geometry is what makes it slot into path walls with no special cases: a quarter circle of
 * radius 1 whose endpoints are two diagonal cell corners is tangent, at those exact corners, to
 * the face planes of cardinal cabinets on both sides — so the picture flows around the corner
 * with the same corner-to-corner continuity as the 45° chamfer cabinets. {@code FACING} names
 * the cell corner being wrapped: the one between FACING and FACING-clockwise.
 *
 * Extends the panel block so it shares the block entity, config sync and DMX plumbing of the
 * wall it belongs to; the scanners treat it as a universal joint rather than a same-kind panel.
 */
public class LedCornerBlock extends LedPanelBlock {

    /** True = outside (convex) corner; false = inside (concave). */
    public static final BooleanProperty CONVEX = BooleanProperty.create("convex");

    public LedCornerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(CONVEX, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CONVEX);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState base = super.getStateForPlacement(ctx);
        if (base == null) {
            return null;
        }
        boolean inner = ctx.getPlayer() != null && ctx.getPlayer().isShiftKeyDown();
        base = base.setValue(DIAGONAL, false).setValue(CONVEX, !inner);

        // Auto-orient: only one of the four rotations joins any given corner, so hunting for it
        // by placement yaw was a lottery. Score each rotation by how many of its arc endpoints
        // land on a neighbouring cabinet's face endpoint, and take the best; the yaw-derived
        // facing stands only when nothing joins (a corner placed before its walls).
        BlockPos cell = ctx.getClickedPos();
        var level = ctx.getLevel();
        BlockState best = base;
        int bestScore = 0;
        for (Direction f : Direction.Plane.HORIZONTAL) {
            BlockState candidate = base.setValue(FACING, f);
            double[] seg = pathSeg(cell, candidate, false);
            int score = WallScanner.endpointNeighbours(level, cell, seg[0], seg[1])
                    + WallScanner.endpointNeighbours(level, cell, seg[2], seg[3]);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Re-orients when the world changes around it, so building order stops mattering: a corner
     * placed before its walls snaps into the joining rotation the moment a wall arrives. Only
     * ever rotates to a strictly better fit, so a settled corner never flaps.
     */
    @Override
    public void neighborChanged(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                                Block neighbor, BlockPos neighborPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moving);
        if (level.isClientSide) {
            return;
        }
        Direction bestF = state.getValue(FACING);
        int bestScore = scoreFacing(level, pos, state, bestF);
        for (Direction f : Direction.Plane.HORIZONTAL) {
            int score = scoreFacing(level, pos, state, f);
            if (score > bestScore) {
                bestScore = score;
                bestF = f;
            }
        }
        if (bestF != state.getValue(FACING)) {
            level.setBlock(pos, state.setValue(FACING, bestF), 3);
        }
    }

    private static int scoreFacing(net.minecraft.world.level.Level level, BlockPos pos,
                                   BlockState state, Direction f) {
        double[] seg = pathSeg(pos, state.setValue(FACING, f), false);
        return WallScanner.endpointNeighbours(level, pos, seg[0], seg[1])
                + WallScanner.endpointNeighbours(level, pos, seg[2], seg[3]);
    }

    /**
     * The corner cabinet's face for path scanning and rendering, as
     * {@code {leftX, leftZ, rightX, rightZ, centerX, centerZ, sign, 0}} — endpoints of the
     * quarter arc, its centre, and the outward-normal sign (+1 convex bulging toward the
     * wrapped corner, -1 concave curving away from it). A corner has no inherent direction, so
     * both orientations exist; the scanner adopts whichever matches the approaching wall.
     */
    public static double[] pathSeg(BlockPos cell, BlockState state, boolean flipped) {
        Direction f = state.getValue(FACING);
        Direction cw = f.getClockWise();
        int kdx = (f.getStepX() + cw.getStepX()) > 0 ? 1 : 0;
        int kdz = (f.getStepZ() + cw.getStepZ()) > 0 ? 1 : 0;
        double kx = cell.getX() + kdx;
        double kz = cell.getZ() + kdz;
        // The two cell corners adjacent to K, i.e. the arc's endpoints.
        double q1x = cell.getX() + kdx;
        double q1z = cell.getZ() + (1 - kdz);
        double q2x = cell.getX() + (1 - kdx);
        double q2z = cell.getZ() + kdz;
        boolean convex = state.getValue(CONVEX);
        double cx = convex ? cell.getX() + (1 - kdx) : kx;
        double cz = convex ? cell.getZ() + (1 - kdz) : kz;
        double sign = convex ? 1.0 : -1.0;
        return flipped
                ? new double[]{q2x, q2z, q1x, q1z, cx, cz, sign, 0.0}
                : new double[]{q1x, q1z, q2x, q2z, cx, cz, sign, 0.0};
    }
}
