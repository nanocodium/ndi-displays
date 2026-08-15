package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects rectangular floors of contiguous LED floor tiles sharing a FACING
 * (the direction of the image's top, v=0). Axes when looking down: right =
 * {@link Direction#getClockWise()} of FACING, up-of-image = FACING itself.
 * The anchor is the image's bottom-left tile (nothing to its left, nothing
 * toward the image's bottom).
 */
public final class FloorScanner {

    public static final int MAX_SPAN = 64;

    /**
     * @param width  tiles along viewer-right (clockwise of facing)
     * @param depth  tiles along facing (image top)
     */
    public record FloorInfo(BlockPos anchor, Direction facing, int width, int depth) {
    }

    private FloorScanner() {
    }

    public static boolean isTile(BlockGetter level, BlockPos pos, Direction facing, Block kind) {
        BlockState state = stateIfLoaded(level, pos);
        return state != null && state.is(kind)
                && state.hasProperty(LedFloorBlock.FACING)
                && state.getValue(LedFloorBlock.FACING) == facing;
    }

    private static BlockState stateIfLoaded(BlockGetter level, BlockPos pos) {
        if (level instanceof LevelReader reader && !reader.hasChunkAt(pos)) {
            return null;
        }
        return level.getBlockState(pos);
    }

    /** Image-left: counter-clockwise of FACING when looking down. */
    public static Direction left(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Image-right: clockwise of FACING when looking down. */
    public static Direction right(Direction facing) {
        return facing.getClockWise();
    }

    /** Image-bottom: opposite of FACING. */
    public static Direction back(Direction facing) {
        return facing.getOpposite();
    }

    public static BlockPos findAnchor(BlockGetter level, BlockPos pos, Direction facing, Block kind) {
        BlockPos cursor = pos;
        for (int guard = 0; guard < MAX_SPAN * 2; guard++) {
            if (isTile(level, cursor.relative(left(facing)), facing, kind)) {
                cursor = cursor.relative(left(facing));
            } else if (isTile(level, cursor.relative(back(facing)), facing, kind)) {
                cursor = cursor.relative(back(facing));
            } else {
                return cursor;
            }
        }
        return cursor;
    }

    public static FloorInfo scan(BlockGetter level, BlockPos anchor, Direction facing, Block kind) {
        Direction r = right(facing);
        int width = 1;
        while (width < MAX_SPAN && isTile(level, anchor.relative(r, width), facing, kind)) {
            width++;
        }
        int depth = 1;
        outer:
        while (depth < MAX_SPAN) {
            for (int x = 0; x < width; x++) {
                if (!isTile(level, anchor.relative(facing, depth).relative(r, x), facing, kind)) {
                    break outer;
                }
            }
            depth++;
        }
        return new FloorInfo(anchor, facing, width, depth);
    }

    public static boolean isIsolatedRectangle(BlockGetter level, FloorInfo floor, Block kind) {
        Direction facing = floor.facing();
        Direction r = right(facing);
        BlockPos anchor = floor.anchor();
        for (int d = 0; d < floor.depth(); d++) {
            BlockPos row = anchor.relative(facing, d);
            if (isTile(level, row.relative(left(facing)), facing, kind)
                    || isTile(level, row.relative(r, floor.width()), facing, kind)) {
                return false;
            }
        }
        for (int x = 0; x < floor.width(); x++) {
            BlockPos col = anchor.relative(r, x);
            if (isTile(level, col.relative(back(facing)), facing, kind)
                    || isTile(level, col.relative(facing, floor.depth()), facing, kind)) {
                return false;
            }
        }
        return true;
    }

    public static boolean contains(FloorInfo floor, BlockPos pos) {
        if (pos.getY() != floor.anchor().getY()) {
            return false;
        }
        Direction facing = floor.facing();
        Direction r = right(facing);
        BlockPos d = pos.subtract(floor.anchor());
        int across = d.getX() * r.getStepX() + d.getZ() * r.getStepZ();
        int along = d.getX() * facing.getStepX() + d.getZ() * facing.getStepZ();
        return across >= 0 && across < floor.width() && along >= 0 && along < floor.depth();
    }

    public static List<BlockPos> collectGroup(BlockGetter level, BlockPos start, Direction facing, Block kind) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        int limit = MAX_SPAN * MAX_SPAN;
        Direction r = right(facing);
        Direction l = left(facing);
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (BlockPos next : new BlockPos[]{
                    pos.relative(r), pos.relative(l), pos.relative(facing), pos.relative(back(facing))}) {
                BlockPos key = next.immutable();
                if (seen.add(key) && isTile(level, key, facing, kind)) {
                    queue.add(key);
                }
            }
        }
        return found;
    }
}
