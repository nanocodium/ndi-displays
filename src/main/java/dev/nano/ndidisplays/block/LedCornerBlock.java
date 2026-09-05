package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    /**
     * Convex facing=north wraps the NE corner: north-facing slab (south edge) + east-facing
     * slab (west edge). Inner facing=north lines that same corner from inside: the two outer
     * walls (north + east edges).
     */
    private static final VoxelShape CONVEX_NORTH = Shapes.or(
            Block.box(0, 0, 14, 16, 16, 16),
            Block.box(0, 0, 0, 2, 16, 14));
    private static final VoxelShape INNER_NORTH = Shapes.or(
            Block.box(0, 0, 0, 16, 16, 2),
            Block.box(14, 0, 2, 16, 16, 16));
    private static final VoxelShape CONVEX_EAST = rotateY(CONVEX_NORTH, 1);
    private static final VoxelShape CONVEX_SOUTH = rotateY(CONVEX_NORTH, 2);
    private static final VoxelShape CONVEX_WEST = rotateY(CONVEX_NORTH, 3);
    private static final VoxelShape INNER_EAST = rotateY(INNER_NORTH, 1);
    private static final VoxelShape INNER_SOUTH = rotateY(INNER_NORTH, 2);
    private static final VoxelShape INNER_WEST = rotateY(INNER_NORTH, 3);

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
        // Facing comes from the click / look direction, like a flat panel. Snap only when
        // BOTH wings of the L already exist (score >= 2); a single neighbour used to rotate
        // the wrap onto another cell corner and shift the picture by a block.
        base = base.setValue(DIAGONAL, false).setValue(CONVEX, !inner);
        return orientToNeighbours(ctx.getLevel(), ctx.getClickedPos(), base);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos fromPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbor, fromPos, movedByPiston);
        if (level.isClientSide) {
            return;
        }
        BlockState next = orientToNeighbours(level, pos, state);
        if (next != state) {
            level.setBlock(pos, next, 3);
        }
    }

    /**
     * Rotate the wrap-corner so both arc endpoints land on neighbouring face endpoints.
     * Score 0 or 1 leaves the player's facing alone.
     */
    private static BlockState orientToNeighbours(Level level, BlockPos cell, BlockState state) {
        BlockState best = state;
        int bestScore = endpointScore(level, cell, state);
        for (Direction f : Direction.Plane.HORIZONTAL) {
            BlockState candidate = state.setValue(FACING, f);
            int score = endpointScore(level, cell, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 2 ? best : state;
    }

    private static int endpointScore(Level level, BlockPos cell, BlockState state) {
        double[] seg = pathSeg(cell, state, false);
        return WallScanner.endpointNeighbours(level, cell, seg[0], seg[1])
                + WallScanner.endpointNeighbours(level, cell, seg[2], seg[3]);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        boolean convex = state.getValue(CONVEX);
        return switch (state.getValue(FACING)) {
            case EAST -> convex ? CONVEX_EAST : INNER_EAST;
            case SOUTH -> convex ? CONVEX_SOUTH : INNER_SOUTH;
            case WEST -> convex ? CONVEX_WEST : INNER_WEST;
            default -> convex ? CONVEX_NORTH : INNER_NORTH;
        };
    }

    /** 90° clockwise steps of an XZ shape about the cell centre. */
    private static VoxelShape rotateY(VoxelShape shape, int steps) {
        VoxelShape out = Shapes.empty();
        int s = Math.floorMod(steps, 4);
        for (var box : shape.toAabbs()) {
            double minX = box.minX, minZ = box.minZ, maxX = box.maxX, maxZ = box.maxZ;
            for (int i = 0; i < s; i++) {
                double nMinX = 1.0 - maxZ, nMinZ = minX, nMaxX = 1.0 - minZ, nMaxZ = maxX;
                minX = nMinX;
                minZ = nMinZ;
                maxX = nMaxX;
                maxZ = nMaxZ;
            }
            out = Shapes.or(out, Shapes.box(minX, box.minY, minZ, maxX, box.maxY, maxZ));
        }
        return out;
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
