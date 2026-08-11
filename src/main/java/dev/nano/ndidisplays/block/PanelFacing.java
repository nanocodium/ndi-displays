package dev.nano.ndidisplays.block;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One of eight horizontal screen orientations: the four cardinals, plus the four 45° diagonals
 * for stage walls whose wings are set at an angle to the main surface.
 *
 * A diagonal wall is a staircase of diagonally-adjacent blocks, each holding a cabinet turned
 * 45° that spans its block's <em>full</em> diagonal (16·√2 ≈ 22.63px, not 16). That length is
 * what makes the surface continuous rather than zigzagged: consecutive blocks in the staircase
 * share exactly one corner, so cabinet edges meet there and the emissive planes join into one
 * unbroken 45° plane. A 16px-wide cabinet would leave a gap at every step.
 *
 * The wall axes come from one formula, {@code right = (n.z, 0, -n.x)}, which reproduces
 * {@code facing.getCounterClockWise()} for the cardinals and gives the diagonal neighbour step
 * for the rest. The scanner and renderer therefore need no special cases — only the stride
 * between panels ({@link #pitch()}) and the surface's offset within its block differ.
 */
public enum PanelFacing {

    // Cardinal, then the diagonal 45° counter-clockwise of it. That pairing is what lets a
    // blockstate carry a plain FACING plus a DIAGONAL flag instead of an eight-value property,
    // so existing cardinal walls keep their exact blockstates and stay loadable.
    NORTH(Direction.NORTH, false),
    NORTH_WEST(Direction.NORTH, true),
    WEST(Direction.WEST, false),
    SOUTH_WEST(Direction.WEST, true),
    SOUTH(Direction.SOUTH, false),
    SOUTH_EAST(Direction.SOUTH, true),
    EAST(Direction.EAST, false),
    NORTH_EAST(Direction.EAST, true);

    /**
     * Indexed by the player's yaw quantised to 45°, giving the orientation that faces back at
     * them. Yaw 0 is looking south, and increases toward west.
     */
    private static final PanelFacing[] BY_YAW_SECTOR = {
            NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST
    };

    private final Direction cardinal;
    private final boolean diagonal;
    private final Vec3i normalStep;
    private final Vec3i rightStep;
    private final Vec3 normal;
    private final Vec3 rightUnit;
    private final double pitch;

    PanelFacing(Direction cardinal, boolean diagonal) {
        this.cardinal = cardinal;
        this.diagonal = diagonal;
        if (diagonal) {
            Direction ccw = cardinal.getCounterClockWise();
            this.normalStep = new Vec3i(
                    cardinal.getStepX() + ccw.getStepX(), 0, cardinal.getStepZ() + ccw.getStepZ());
        } else {
            this.normalStep = cardinal.getNormal();
        }
        this.rightStep = new Vec3i(normalStep.getZ(), 0, -normalStep.getX());
        double len = Math.sqrt((double) normalStep.getX() * normalStep.getX()
                + (double) normalStep.getZ() * normalStep.getZ());
        this.normal = new Vec3(normalStep.getX() / len, 0.0, normalStep.getZ() / len);
        this.rightUnit = new Vec3(rightStep.getX() / len, 0.0, rightStep.getZ() / len);
        // Math.sqrt inline rather than a constant: an enum constructor runs before the class's
        // static fields are initialised, so it cannot read one.
        this.pitch = diagonal ? Math.sqrt(2.0) : 1.0;
    }

    public static PanelFacing of(BlockState state) {
        Direction cardinal = state.getValue(LedPanelBlock.FACING);
        boolean diag = state.getValue(LedPanelBlock.DIAGONAL);
        return values()[cardinal2index(cardinal) * 2 + (diag ? 1 : 0)];
    }

    private static int cardinal2index(Direction cardinal) {
        return switch (cardinal) {
            case NORTH -> 0;
            case WEST -> 1;
            case SOUTH -> 2;
            default -> 3; // EAST
        };
    }

    /** The orientation whose screen points back at a player looking along {@code yRot}. */
    public static PanelFacing facingPlayer(float yRot) {
        return BY_YAW_SECTOR[Math.floorMod(Math.round(yRot / 45.0F), 8)];
    }

    public Direction cardinal() {
        return cardinal;
    }

    public boolean isDiagonal() {
        return diagonal;
    }

    /** Integer neighbour step toward the viewer's right; a diagonal step when diagonal. */
    public Vec3i rightStep() {
        return rightStep;
    }

    /** Unnormalised outward normal in whole blocks — exact, for coplanarity tests. */
    public Vec3i normalStep() {
        return normalStep;
    }

    /** Unit outward normal. */
    public Vec3 normal() {
        return normal;
    }

    /** Unit vector toward the viewer's right. */
    public Vec3 rightUnit() {
        return rightUnit;
    }

    /** Distance between adjacent panels along the wall: 1 block, or √2 when diagonal. */
    public double pitch() {
        return pitch;
    }

    /**
     * Where the emissive surface sits relative to its block's centre, measured along the
     * outward normal.
     *
     * Cardinal cabinets hug the back of their cell so a wall sits flush with the block
     * boundary, which puts the screen <em>behind</em> centre (a negative offset). Diagonal
     * cabinets straddle the block's diagonal, so their screen sits just in front of centre.
     */
    public double surfaceOffset(float thickness, float epsilon) {
        return diagonal
                ? thickness * 0.5 + epsilon
                : -(0.5 - thickness - epsilon);
    }
}
