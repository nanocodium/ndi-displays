package dev.nano.ndidisplays.winch;

import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Physical layout of a kinetic-winch park: project motors onto XZ, snap them to a
 * grid of unique coordinates, and optionally stamp that grid onto each winch's
 * shared-canvas UV so a selection of tiles or slats reconstitutes one image.
 */
public final class WinchParkLayout {

    public static final int MAX_SPAN = 512;

    /**
     * One motor in the park. {@code gridX} runs west→east, {@code gridZ} north→south
     * (row 0 is the northernmost / smallest Z, so a top-down view matches Minecraft).
     */
    public record Motor(BlockPos pos, int gridX, int gridZ, int cols, int rows,
                        KineticWinchBlockEntity be) {
    }

    private WinchParkLayout() {
    }

    public static List<KineticWinchBlockEntity> collect(Level level, BlockPos a, BlockPos b) {
        List<KineticWinchBlockEntity> list = new ArrayList<>();
        if (level == null || a == null || b == null) {
            return list;
        }
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        if (maxX - minX > MAX_SPAN || maxZ - minZ > MAX_SPAN) {
            return list;
        }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (pos.getX() < minX || pos.getX() > maxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    if (be instanceof KineticWinchBlockEntity winch) {
                        list.add(winch);
                    }
                }
            }
        }
        list.sort(Comparator.comparingInt((KineticWinchBlockEntity w) -> w.getBlockPos().getZ())
                .thenComparingInt(w -> w.getBlockPos().getX()));
        return list;
    }

    public static List<Motor> layout(List<KineticWinchBlockEntity> winches) {
        List<Motor> motors = new ArrayList<>(winches.size());
        if (winches.isEmpty()) {
            return motors;
        }
        TreeSet<Integer> xs = new TreeSet<>();
        TreeSet<Integer> zs = new TreeSet<>();
        for (KineticWinchBlockEntity w : winches) {
            xs.add(w.getBlockPos().getX());
            zs.add(w.getBlockPos().getZ());
        }
        List<Integer> xList = new ArrayList<>(xs);
        List<Integer> zList = new ArrayList<>(zs);
        int cols = xList.size();
        int rows = zList.size();
        for (KineticWinchBlockEntity w : winches) {
            BlockPos pos = w.getBlockPos();
            motors.add(new Motor(pos, xList.indexOf(pos.getX()), zList.indexOf(pos.getZ()),
                    cols, rows, w));
        }
        return motors;
    }

    /**
     * Stamps canvas UV from the park as seen on the tiles themselves — not raw world
     * X/Z. Column 0 is the viewer's left (u=0), matching {@code KineticPanelRenderer}:
     * u increases counter-clockwise of FACING, v=0 sits on the +FACING edge.
     *
     * A line of slats along that U axis becomes N columns × 1 row, so each motor
     * shows its own vertical slice instead of one giant screen sliced into rows.
     */
    public static void applyCanvasMap(List<KineticWinchBlockEntity> winches) {
        if (winches.isEmpty()) {
            return;
        }
        Direction facing = dominantFacing(winches);
        Direction colDir = facing.getCounterClockWise();
        Direction rowDir = facing.getOpposite();
        TreeSet<Integer> colKeys = new TreeSet<>();
        TreeSet<Integer> rowKeys = new TreeSet<>();
        for (KineticWinchBlockEntity w : winches) {
            BlockPos pos = w.getBlockPos();
            colKeys.add(axisKey(pos, colDir));
            rowKeys.add(axisKey(pos, rowDir));
        }
        List<Integer> colList = new ArrayList<>(colKeys);
        List<Integer> rowList = new ArrayList<>(rowKeys);
        int cols = colList.size();
        int rows = rowList.size();
        for (KineticWinchBlockEntity w : winches) {
            BlockPos pos = w.getBlockPos();
            w.applyCanvasMapping(cols, rows,
                    colList.indexOf(axisKey(pos, colDir)),
                    rowList.indexOf(axisKey(pos, rowDir)));
        }
    }

    /** Most common horizontal facing in the park; ties break on the first motor. */
    private static Direction dominantFacing(List<KineticWinchBlockEntity> winches) {
        EnumMap<Direction, Integer> counts = new EnumMap<>(Direction.class);
        Direction first = winches.get(0).getFacing();
        for (KineticWinchBlockEntity w : winches) {
            Direction facing = w.getFacing();
            counts.merge(facing, 1, Integer::sum);
        }
        Direction best = first;
        int bestCount = 0;
        for (Map.Entry<Direction, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static int axisKey(BlockPos pos, Direction dir) {
        return pos.getX() * dir.getStepX() + pos.getZ() * dir.getStepZ();
    }
}
