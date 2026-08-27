package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
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

    /**
     * Shapes above this tile count fall back to the classic rectangle rules. The flood fill runs
     * per panel every two seconds, so the cap bounds worst-case scan cost; a plain rectangle
     * larger than this still works because the fallback path handles it.
     */
    public static final int SHAPE_TILE_LIMIT = 8192;

    /**
     * @param anchor      the RENDER-ANCHOR TILE — the shape's bottom-most, then left-most panel.
     *                    For a rectangle this is the bottom-left corner; for a cross or ring it
     *                    is simply a real tile, NOT necessarily the bounding box corner.
     * @param tiles       which bounding-box cells hold a panel, row-major (y * width + x), or
     *                    null when the shape fills its box completely (plain rectangle)
     * @param anchorAcross the anchor tile's cell x within the bounding box
     * @param anchorUp     the anchor tile's cell y within the bounding box
     */
    public record WallInfo(BlockPos anchor, PanelFacing facing, int width, int height,
                           java.util.BitSet tiles, int anchorAcross, int anchorUp,
                           java.util.List<BlockPos> pathColumns,
                           java.util.List<PanelFacing> pathFacings,
                           double[] pathSegs) {

        /** Plain rectangle, for callers predating shaped walls. */
        public WallInfo(BlockPos anchor, PanelFacing facing, int width, int height) {
            this(anchor, facing, width, height, null, 0, 0, null, null, null);
        }

        /** Planar shape (single orientation, arbitrary silhouette). */
        public WallInfo(BlockPos anchor, PanelFacing facing, int width, int height,
                        java.util.BitSet tiles, int anchorAcross, int anchorUp) {
            this(anchor, facing, width, height, tiles, anchorAcross, anchorUp, null, null, null);
        }

        /**
         * A wall that bends: column {@code i} sits at {@code pathColumns.get(i)} (y = the
         * bounding row 0) with its own orientation. Width is the column count; each column is
         * one cabinet of the frame regardless of orientation, exactly as the diagonal cabinets
         * already work — a bend never stretches the picture.
         */
        public boolean isPath() {
            return pathColumns != null;
        }

        public boolean isShaped() {
            return tiles != null;
        }

        /** Whether the bounding-box cell (x, y) holds a panel. */
        public boolean has(int x, int y) {
            return tiles == null || tiles.get(y * width + x);
        }

        /** The bounding box's bottom-left corner position (may be air on a shaped wall). */
        public BlockPos origin() {
            Vec3i right = facing.rightStep();
            return anchor.offset(-right.getX() * anchorAcross, -anchorUp, -right.getZ() * anchorAcross);
        }
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
        List<BlockPos> list = new ArrayList<>(wall.width() * wall.height());
        if (wall.isPath()) {
            for (int y = 0; y < wall.height(); y++) {
                for (int x = 0; x < wall.width(); x++) {
                    if (wall.has(x, y)) {
                        list.add(wall.pathColumns().get(x).above(y));
                    }
                }
            }
            return list;
        }
        Vec3i right = wall.facing().rightStep();
        BlockPos origin = wall.origin();
        for (int y = 0; y < wall.height(); y++) {
            for (int x = 0; x < wall.width(); x++) {
                if (wall.has(x, y)) {
                    list.add(step(origin.above(y), right, x));
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ path walls
    //
    // A wall that turns corners. Columns chain wherever their idealised face segments share a
    // cell-corner endpoint (top view): a cardinal cabinet's face spans its cell's front edge, a
    // diagonal cabinet's face spans the cell's full diagonal — so a flat run, a 45° chamfer and
    // the flat run beyond it meet corner-to-corner and read as ONE continuous screen, the way
    // real chamfered stage walls are built. Detection is purely geometric, so future corner
    // cabinets only need to contribute their own segment endpoints.

    /**
     * Idealised face segment endpoints of the cabinet at {@code cell}, top view, as
     * {@code {leftX, leftZ, rightX, rightZ}} in the viewer's left-to-right order.
     */
    /**
     * Idealised face of the cabinet at {@code cell}, top view, as
     * {@code {leftX, leftZ, rightX, rightZ, arcCX, arcCZ, arcSign, 0}}. Straight cabinets carry
     * NaN arc fields; corner cabinets carry their quarter-arc centre and normal sign.
     */
    private static double[] faceSegment(BlockPos cell, PanelFacing facing) {
        double cx = cell.getX() + 0.5;
        double cz = cell.getZ() + 0.5;
        Vec3 n = facing.normal();
        Vec3 r = facing.rightUnit();
        double half = facing.pitch() * 0.5;
        // Cardinal faces sit on the cell's front edge; diagonal faces span the cell diagonal
        // through its centre (their normal offset is zero in this idealisation).
        double off = facing.isDiagonal() ? 0.0 : 0.5;
        double fx = cx + n.x * off;
        double fz = cz + n.z * off;
        return new double[]{fx - r.x * half, fz - r.z * half, fx + r.x * half, fz + r.z * half,
                Double.NaN, Double.NaN, 1.0, 0.0};
    }

    private static boolean samePoint(double ax, double az, double bx, double bz) {
        return Math.abs(ax - bx) < 1.0E-4 && Math.abs(az - bz) < 1.0E-4;
    }

    /**
     * Whether a chain may step from orientation {@code a} to {@code b}. Same orientation is a
     * straight run; a change is only allowed THROUGH a diagonal or corner cabinet — the bridging
     * cabinet is the builder's explicit "these connect". Direct cardinal-to-cardinal turns are
     * forbidden on purpose: two independent flat screens meeting at a corner is everyday stage
     * building, and an endpoint match alone must never merge them into one mis-mapped wall.
     * A null orientation is a corner cabinet, which bridges anything.
     */
    private static boolean mayTurn(PanelFacing a, PanelFacing b) {
        return a == null || b == null || a == b || a.isDiagonal() || b.isDiagonal();
    }

    /**
     * The unique cabinet whose face endpoint coincides with {@code (x, z)} — the next column
     * ({@code matchLeft}) or the previous one. Corner cabinets are direction-free, so both of
     * their orientations are tried and the matching one adopted. Ambiguity (a T-junction) ends
     * the path, so every panel of a component reconstructs the same chain from any start.
     *
     * @return {@code {BlockPos, PanelFacing or null, double[8] seg}} or null
     */
    private static Object[] matchColumn(BlockGetter level, BlockPos from, PanelFacing fromFacing,
                                        double x, double z, Block kind, BlockPos exclude,
                                        boolean matchLeft) {
        java.util.List<Object[]> candidates = new ArrayList<>(2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos cell = from.offset(dx, 0, dz);
                if (cell.equals(exclude)) {
                    continue;
                }
                BlockState st = stateIfLoaded(level, cell);
                if (st == null) {
                    continue;
                }
                if (st.getBlock() instanceof LedCornerBlock) {
                    for (boolean flip : new boolean[]{false, true}) {
                        double[] seg = LedCornerBlock.pathSeg(cell, st, flip);
                        double px = matchLeft ? seg[0] : seg[2];
                        double pz = matchLeft ? seg[1] : seg[3];
                        if (samePoint(px, pz, x, z)) {
                            candidates.add(new Object[]{cell.immutable(), null, seg});
                        }
                    }
                    continue;
                }
                for (PanelFacing g : PanelFacing.values()) {
                    if (!isChainPanel(level, cell, g, kind) || !mayTurn(fromFacing, g)) {
                        continue;
                    }
                    double[] seg = faceSegment(cell, g);
                    double px = matchLeft ? seg[0] : seg[2];
                    double pz = matchLeft ? seg[1] : seg[3];
                    if (samePoint(px, pz, x, z)) {
                        candidates.add(new Object[]{cell.immutable(), g, seg});
                    }
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            // A junction. Preferring the straight panel keeps the common cases building — a
            // corner whose endpoint also brushes another corner would otherwise end the chain.
            // Only a junction of several straights (a genuine T) stays ambiguous and stops.
            Object[] straight = null;
            for (Object[] c : candidates) {
                if (c[1] != null) {
                    if (straight != null) {
                        return null;
                    }
                    straight = c;
                }
            }
            return straight;
        }
        return null;
    }

    /**
     * A straight cabinet eligible to join a path chain. A null {@code kind} is the wildcard used
     * when the scan starts on a corner cabinet, before the chain kind is known: any panel
     * matches, and the walk locks onto the first one it meets.
     */
    private static boolean isChainPanel(BlockGetter level, BlockPos cell, PanelFacing g, Block kind) {
        BlockState st = stateIfLoaded(level, cell);
        if (st == null || st.getBlock() instanceof LedCornerBlock) {
            return false;
        }
        if (kind == null ? !(st.getBlock() instanceof LedPanelBlock) : !st.is(kind)) {
            return false;
        }
        return PanelFacing.of(st) == g;
    }

    /**
     * How many neighbouring cabinets have a face endpoint at {@code (x, z)} — the corner
     * block's placement scorer. Counts panels of any kind and other corner cabinets alike.
     */
    public static int endpointNeighbours(BlockGetter level, BlockPos around, double x, double z) {
        int score = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos cell = around.offset(dx, 0, dz);
                BlockState st = stateIfLoaded(level, cell);
                if (st == null) {
                    continue;
                }
                if (st.getBlock() instanceof LedCornerBlock) {
                    double[] seg = LedCornerBlock.pathSeg(cell, st, false);
                    if (samePoint(seg[0], seg[1], x, z) || samePoint(seg[2], seg[3], x, z)) {
                        score++;
                    }
                } else if (st.getBlock() instanceof LedPanelBlock) {
                    double[] seg = faceSegment(cell, PanelFacing.of(st));
                    if (samePoint(seg[0], seg[1], x, z) || samePoint(seg[2], seg[3], x, z)) {
                        score++;
                    }
                }
            }
        }
        return score;
    }

    /** Whether the cabinet at {@code pos} is exactly the reference cabinet (block + state). */
    private static boolean sameCabinet(BlockGetter level, BlockPos pos, BlockState ref) {
        return stateIfLoaded(level, pos) == ref;
    }

    /**
     * Scans the bending wall through {@code start}, or returns null when the horizontal chain
     * never changes orientation — planar walls stay on the classic (cheaper, recessed-cabinet)
     * path. The chain is walked at the start row's y level; each column then grows vertically
     * over identical cabinets stacked above and below it.
     */
    public static WallInfo scanPath(BlockGetter level, BlockPos start, PanelFacing startFacing, Block kind) {
        BlockState startState = stateIfLoaded(level, start);
        if (startState == null) {
            return null;
        }
        boolean startIsCorner = startState.getBlock() instanceof LedCornerBlock;
        double[] startSeg = startIsCorner
                ? LedCornerBlock.pathSeg(start, startState, false)
                : faceSegment(start, startFacing);
        PanelFacing startF = startIsCorner ? null : startFacing;

        java.util.ArrayDeque<BlockPos> cols = new java.util.ArrayDeque<>();
        // LinkedList, not ArrayDeque: a corner cabinet's facing is null, and ArrayDeque
        // rejects null elements outright — this crashed the render thread the moment a
        // corner joined a chain.
        java.util.LinkedList<PanelFacing> faces = new java.util.LinkedList<>();
        java.util.ArrayDeque<double[]> segs = new java.util.ArrayDeque<>();
        cols.add(start.immutable());
        faces.add(startF);
        segs.add(startSeg);
        Set<BlockPos> seen = new HashSet<>();
        seen.add(start.immutable());
        boolean mixed = startIsCorner;

        // A corner-started scan does not yet know which panel kind its wall is; the first
        // straight cabinet met locks it, so a corner cannot splice two different kinds together.
        Block lockKind = startIsCorner ? null : kind;

        BlockPos cur = start;
        PanelFacing curF = startF;
        double[] curSeg = startSeg;
        BlockPos prev = null;
        for (int guard = 0; guard < MAX_SPAN; guard++) {
            Object[] next = matchColumn(level, cur, curF, curSeg[2], curSeg[3], lockKind, prev, true);
            if (next == null || !seen.add((BlockPos) next[0])) {
                break;
            }
            prev = cur;
            cur = (BlockPos) next[0];
            curF = (PanelFacing) next[1];
            curSeg = (double[]) next[2];
            cols.addLast(cur);
            faces.addLast(curF);
            segs.addLast(curSeg);
            mixed |= curF != startFacing;
            if (lockKind == null && curF != null) {
                lockKind = panelKind(level, cur);
            }
        }
        cur = start;
        curF = startF;
        curSeg = startSeg;
        prev = null;
        for (int guard = 0; guard < MAX_SPAN; guard++) {
            Object[] prevCol = matchColumn(level, cur, curF, curSeg[0], curSeg[1], lockKind, prev, false);
            if (prevCol == null || !seen.add((BlockPos) prevCol[0])) {
                break;
            }
            prev = cur;
            cur = (BlockPos) prevCol[0];
            curF = (PanelFacing) prevCol[1];
            curSeg = (double[]) prevCol[2];
            cols.addFirst(cur);
            faces.addFirst(curF);
            segs.addFirst(curSeg);
            mixed |= curF != startFacing;
            if (lockKind == null && curF != null) {
                lockKind = panelKind(level, cur);
            }
        }
        if (!mixed) {
            return null;
        }

        List<BlockPos> columns = new ArrayList<>(cols);
        List<PanelFacing> facings = new ArrayList<>(faces);
        List<double[]> segList = new ArrayList<>(segs);
        int width = columns.size();

        // vertical extent per column: identical cabinets stacked at the column's (x, z)
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int[] lo = new int[width];
        int[] hi = new int[width];
        BlockState[] refs = new BlockState[width];
        for (int i = 0; i < width; i++) {
            BlockPos c = columns.get(i);
            refs[i] = stateIfLoaded(level, c);
            int down = 0;
            while (down < MAX_SPAN && sameCabinet(level, c.below(down + 1), refs[i])) {
                down++;
            }
            int up = 0;
            while (up < MAX_SPAN && sameCabinet(level, c.above(up + 1), refs[i])) {
                up++;
            }
            lo[i] = c.getY() - down;
            hi[i] = c.getY() + up;
            minY = Math.min(minY, lo[i]);
            maxY = Math.max(maxY, hi[i]);
        }
        int height = maxY - minY + 1;
        if (height > MAX_SPAN || width * height >= SHAPE_TILE_LIMIT) {
            return null;
        }
        java.util.BitSet tiles = new java.util.BitSet(width * height);
        BlockPos anchor = null;
        PanelFacing anchorFacing = null;
        int anchorX = 0;
        int anchorY = 0;
        List<BlockPos> baseCols = new ArrayList<>(width);
        double[] flatSegs = new double[width * 8];
        for (int i = 0; i < width; i++) {
            System.arraycopy(segList.get(i), 0, flatSegs, i * 8, 8);
            BlockPos base = new BlockPos(columns.get(i).getX(), minY, columns.get(i).getZ());
            baseCols.add(base);
            for (int y = lo[i] - minY; y <= hi[i] - minY; y++) {
                if (sameCabinet(level, base.above(y), refs[i])) {
                    tiles.set(y * width + i);
                    if (anchor == null || y < anchorY || (y == anchorY && i < anchorX)) {
                        anchor = base.above(y);
                        anchorFacing = facings.get(i);
                        anchorX = i;
                        anchorY = y;
                    }
                }
            }
        }
        if (anchorFacing == null) {
            // The anchor landed on a corner cabinet; give legacy consumers any real facing.
            for (PanelFacing f : facings) {
                if (f != null) {
                    anchorFacing = f;
                    break;
                }
            }
            if (anchorFacing == null) {
                anchorFacing = PanelFacing.NORTH;
            }
        }
        return new WallInfo(anchor, anchorFacing, width, height,
                tiles, anchorX, anchorY, baseCols, facings, flatSegs);
    }

    /** The idealised face segment of path column {@code i}: {leftX, leftZ, rightX, rightZ}. */
    public static double[] pathSegment(WallInfo wall, int i) {
        double[] s = wall.pathSegs();
        return new double[]{s[i * 8], s[i * 8 + 1], s[i * 8 + 2], s[i * 8 + 3]};
    }

    /**
     * The quarter-arc of path column {@code i} as {centreX, centreZ, normalSign}, or null when
     * the column is a straight cabinet.
     */
    public static double[] pathArc(WallInfo wall, int i) {
        double[] s = wall.pathSegs();
        if (Double.isNaN(s[i * 8 + 4])) {
            return null;
        }
        return new double[]{s[i * 8 + 4], s[i * 8 + 5], s[i * 8 + 6]};
    }

    /**
     * The wall as one connected component of ANY shape — a cross, a ring, letters — scanned by
     * flood fill from {@code start}. The video frame is the shape's bounding box and every tile
     * shows its own cell of it, so a rectangular source is masked by the build itself, exactly
     * how real shaped LED (a Eurovision cross floor) is driven.
     *
     * @return the shape, or null when it exceeds {@link #SHAPE_TILE_LIMIT} or its bounding box
     *         exceeds {@link #MAX_SPAN} — callers then fall back to the rectangle rules
     */
    public static WallInfo scanShape(BlockGetter level, BlockPos start, PanelFacing facing, Block kind) {
        List<BlockPos> group = collectGroup(level, start, facing, kind);
        if (group.size() >= SHAPE_TILE_LIMIT) {
            return null;
        }
        Vec3i right = facing.rightStep();
        int len2 = right.getX() * right.getX() + right.getZ() * right.getZ();
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int[] across = new int[group.size()];
        for (int i = 0; i < group.size(); i++) {
            BlockPos d = group.get(i).subtract(start);
            int a = (d.getX() * right.getX() + d.getZ() * right.getZ()) / len2;
            across[i] = a;
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minY = Math.min(minY, d.getY());
            maxY = Math.max(maxY, d.getY());
        }
        int width = maxA - minA + 1;
        int height = maxY - minY + 1;
        if (width > MAX_SPAN || height > MAX_SPAN) {
            return null;
        }
        java.util.BitSet tiles = new java.util.BitSet(width * height);
        BlockPos anchor = null;
        int anchorA = 0;
        int anchorY = 0;
        for (int i = 0; i < group.size(); i++) {
            int x = across[i] - minA;
            int y = group.get(i).getY() - start.getY() - minY;
            tiles.set(y * width + x);
            if (anchor == null || y < anchorY || (y == anchorY && x < anchorA)) {
                anchor = group.get(i);
                anchorA = x;
                anchorY = y;
            }
        }
        if (tiles.cardinality() == width * height) {
            // A full rectangle: identical to the classic scan, single-quad fast path and all.
            return new WallInfo(anchor, facing, width, height);
        }
        return new WallInfo(anchor, facing, width, height, tiles, anchorA, anchorY);
    }

    /**
     * Horizontal runs of present tiles, one {@code {x0, x1exclusive, y}} per run, bottom row
     * first. The renderers emit one quad per run, so a shape costs a handful of quads rather
     * than one per tile.
     */
    public static List<int[]> runs(WallInfo wall) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < wall.height(); y++) {
            int x = 0;
            while (x < wall.width()) {
                if (!wall.has(x, y)) {
                    x++;
                    continue;
                }
                int x0 = x;
                while (x < wall.width() && wall.has(x, y)) {
                    x++;
                }
                out.add(new int[]{x0, x, y});
            }
        }
        return out;
    }
}
