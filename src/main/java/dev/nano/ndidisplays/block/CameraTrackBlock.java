package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Flat dolly rail. Runs may bend: a track block with perpendicular neighbours becomes a
 * curve, so a run can turn corners or close into a ring for a dolly to circle continuously.
 * The shape is derived from neighbours, like vanilla rails, so builders just place track and
 * the corners appear.
 */
public class CameraTrackBlock extends Block {

    /** Which way the rail runs through this block. */
    public enum Shape implements StringRepresentable {
        NORTH_SOUTH("north_south"),
        EAST_WEST("east_west"),
        NORTH_EAST("north_east"),
        NORTH_WEST("north_west"),
        SOUTH_EAST("south_east"),
        SOUTH_WEST("south_west");

        private final String name;

        Shape(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public boolean isCurve() {
            return this != NORTH_SOUTH && this != EAST_WEST;
        }
    }

    public static final EnumProperty<Shape> SHAPE = EnumProperty.create("shape", Shape.class);

    private static final VoxelShape COLLISION = box(0, 0, 0, 16, 2, 16);

    public CameraTrackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SHAPE, Shape.NORTH_SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SHAPE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(SHAPE,
                shapeFor(context.getLevel(), context.getClickedPos(),
                        context.getHorizontalDirection().getAxis()));
    }

    /**
     * Re-derives this block's shape when a neighbour changes, so completing a corner or
     * removing a rail immediately reshapes the run — the same behaviour rails have.
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (!direction.getAxis().isHorizontal()) {
            return state;
        }
        Shape current = state.getValue(SHAPE);
        Direction.Axis fallback = current == Shape.EAST_WEST ? Direction.Axis.X : Direction.Axis.Z;
        return state.setValue(SHAPE, shapeFor(level, pos, fallback));
    }

    /**
     * Picks the shape from connected neighbours: two perpendicular neighbours make a curve,
     * otherwise the rail runs straight. With no neighbours at all it keeps the orientation
     * the builder placed it with.
     */
    private static Shape shapeFor(LevelReader level, BlockPos pos, Direction.Axis fallbackAxis) {
        boolean north = isTrack(level, pos.north());
        boolean south = isTrack(level, pos.south());
        boolean east = isTrack(level, pos.east());
        boolean west = isTrack(level, pos.west());

        boolean alongZ = north || south;
        boolean alongX = east || west;

        if (alongZ && alongX) {
            // A corner. Prefer the pair actually present; ties resolve consistently.
            if (north && east) {
                return Shape.NORTH_EAST;
            }
            if (north && west) {
                return Shape.NORTH_WEST;
            }
            if (south && east) {
                return Shape.SOUTH_EAST;
            }
            return Shape.SOUTH_WEST;
        }
        if (alongZ) {
            return Shape.NORTH_SOUTH;
        }
        if (alongX) {
            return Shape.EAST_WEST;
        }
        return fallbackAxis == Direction.Axis.X ? Shape.EAST_WEST : Shape.NORTH_SOUTH;
    }

    private static boolean isTrack(LevelReader level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).getBlock() instanceof CameraTrackBlock;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION;
    }

    /** Nudges neighbours to re-evaluate, so a newly placed rail curves the one next to it. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        super.onPlace(state, level, pos, oldState, moving);
        if (!level.isClientSide) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                level.updateNeighborsAt(pos.relative(dir), this);
            }
        }
    }
}
