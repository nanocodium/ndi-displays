package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
 * Detects rectangular walls of contiguous, coplanar LED panels sharing an orientation.
 * The anchor is the bottom row / viewer-left column panel (no panel to its
 * viewer-left, none below). Wall axes: right = {@link PanelFacing#rightStep()}
 * (the viewer's right when looking at the screen), up = +Y.
 *
 * Everything here walks in units of the orientation's right-step, so a 45° wall — whose panels
 * are diagonally adjacent rather than edge-adjacent — is scanned by exactly the same code as a
 * straight one. Only the step itself differs.
 */
public final class WallScanner {

    public static final int MAX_SPAN = 256;

    public record WallInfo(BlockPos anchor, PanelFacing facing, int width, int height) {
    }

    private WallScanner() {
    }

    /** {@code base} moved {@code times} steps along {@code step}. */
    private static BlockPos step(BlockPos base, Vec3i step, int times) {
        return base.offset(step.getX() * times, step.getY() * times, step.getZ() * times);
    }

    /**
     * A panel joins a wall only if it is the *same kind* of cabinet in the same orientation, so
     * solid and blow-through panels placed side by side stay independent walls instead of
     * merging into one mis-mapped video surface — and a diagonal cabinet never merges with the
     * straight one beside it.
     */
    public static boolean isPanel(BlockGetter level, BlockPos pos, PanelFacing facing, Block kind) {
        BlockState state = stateIfLoaded(level, pos);
        return state != null && state.is(kind) && PanelFacing.of(state) == facing;
    }

    /** The cabinet kind at {@code pos}, or null if it is not a panel at all. */
    public static Block panelKind(BlockGetter level, BlockPos pos) {
        BlockState state = stateIfLoaded(level, pos);
        return state != null && state.getBlock() instanceof LedPanelBlock ? state.getBlock() : null;
    }

    /**
     * Never forces a chunk load. Scanning walks outside the wall by design (to find its
     * edges), and on the server {@code getBlockState} on an unloaded position would load
     * that chunk — turning a right-click into cascading chunk loads. Unloaded reads count
     * as "not a panel", which simply ends the wall at the loaded boundary.
     */
    private static BlockState stateIfLoaded(BlockGetter level, BlockPos pos) {
        if (level instanceof LevelReader reader && !reader.hasChunkAt(pos)) {
            return null;
        }
        return level.getBlockState(pos);
    }

    /**
     * Walks viewer-left and down until reaching the wall's bottom-left panel. Bounded by the
     * wall size {@link #scan} will actually accept, so a run longer than MAX_SPAN cannot walk
     * off to an anchor whose scan then excludes the panel we started from.
     */
    public static BlockPos findAnchor(BlockGetter level, BlockPos pos, PanelFacing facing, Block kind) {
        Vec3i left = facing.rightStep();
        BlockPos cursor = pos;
        for (int guard = 0; guard < MAX_SPAN * 2; guard++) {
            if (isPanel(level, step(cursor, left, -1), facing, kind)) {
                cursor = step(cursor, left, -1);
            } else if (isPanel(level, cursor.below(), facing, kind)) {
                cursor = cursor.below();
            } else {
                return cursor;
            }
        }
        return cursor;
    }

    public static boolean isAnchor(BlockGetter level, BlockPos pos, PanelFacing facing, Block kind) {
        return !isPanel(level, step(pos, facing.rightStep(), -1), facing, kind)
                && !isPanel(level, pos.below(), facing, kind);
    }

    /**
     * Measures the largest full rectangle extending right/up from the anchor:
     * width is the bottom-row run length, height grows while every row is complete.
     */
    public static WallInfo scan(BlockGetter level, BlockPos anchor, PanelFacing facing, Block kind) {
        Vec3i right = facing.rightStep();
        int width = 1;
        while (width < MAX_SPAN && isPanel(level, step(anchor, right, width), facing, kind)) {
            width++;
        }
        int height = 1;
        outer:
        while (height < MAX_SPAN) {
            for (int x = 0; x < width; x++) {
                if (!isPanel(level, step(anchor.above(height), right, x), facing, kind)) {
                    break outer;
                }
            }
            height++;
        }
        return new WallInfo(anchor, facing, width, height);
    }

    /**
     * True when nothing of the same kind touches the rectangle's outside edges.
     *
     * This is what keeps a panel from belonging to two walls at once. Break a corner out of
     * a 3x2 and the leftovers yield two different anchors whose rectangles overlap — both
     * would draw a quad over the shared panels, each mapping the video differently. A real
     * standalone wall has nothing but air around it, so anything touching an edge means the
     * arrangement is not a clean rectangle and the panels fall back to rendering
     * individually (which is also what an unmapped LED cabinet does in real life).
     */
    public static boolean isIsolatedRectangle(BlockGetter level, WallInfo wall, Block kind) {
        PanelFacing facing = wall.facing();
        Vec3i right = facing.rightStep();
        BlockPos anchor = wall.anchor();
        for (int y = 0; y < wall.height(); y++) {
            if (isPanel(level, step(anchor.above(y), right, -1), facing, kind)
                    || isPanel(level, step(anchor.above(y), right, wall.width()), facing, kind)) {
                return false;
            }
        }
        for (int x = 0; x < wall.width(); x++) {
            if (isPanel(level, step(anchor.below(), right, x), facing, kind)
                    || isPanel(level, step(anchor.above(wall.height()), right, x), facing, kind)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code pos} lies inside the wall's rectangle, on its exact plane. */
    public static boolean contains(WallInfo wall, BlockPos pos) {
        PanelFacing facing = wall.facing();
        Vec3i normal = facing.normalStep();
        Vec3i right = facing.rightStep();
        BlockPos d = pos.subtract(wall.anchor());
        if (d.getY() < 0 || d.getY() >= wall.height()) {
            return false;
        }
        // Coplanarity: the component along the normal must be zero, or the panel is in a
        // parallel wall behind or in front of this one.
        if (d.getX() * normal.getX() + d.getZ() * normal.getZ() != 0) {
            return false;
        }
        // How many right-steps along the wall. Diagonal steps are not unit length, so divide
        // by the step's squared length and require an exact multiple — a position on the
        // plane but off the staircase (between two panels) is not part of the wall.
        int dot = d.getX() * right.getX() + d.getZ() * right.getZ();
        int len2 = right.getX() * right.getX() + right.getZ() * right.getZ();
        if (dot % len2 != 0) {
            return false;
        }
        int across = dot / len2;
        return across >= 0 && across < wall.width();
    }

    /**
     * Every same-kind, same-orientation panel reachable from {@code start} through shared
     * edges, whatever shape the arrangement is. Used when applying processor settings, so a
     * right-click configures everything the builder thinks of as one screen even if it is
     * not a clean rectangle. Bounded, and never loads chunks.
     */
    public static List<BlockPos> collectGroup(BlockGetter level, BlockPos start, PanelFacing facing, Block kind) {
        Vec3i right = facing.rightStep();
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        int limit = MAX_SPAN * MAX_SPAN;
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (BlockPos next : new BlockPos[]{
                    step(pos, right, 1), step(pos, right, -1), pos.above(), pos.below()}) {
                BlockPos key = next.immutable();
                if (seen.add(key) && isPanel(level, key, facing, kind)) {
                    queue.add(key);
                }
            }
        }
        return found;
    }

    public static List<BlockPos> allPanels(WallInfo wall) {
        Vec3i right = wall.facing().rightStep();
        List<BlockPos> list = new ArrayList<>(wall.width() * wall.height());
        for (int y = 0; y < wall.height(); y++) {
            for (int x = 0; x < wall.width(); x++) {
                list.add(step(wall.anchor().above(y), right, x));
            }
        }
        return list;
    }
}
