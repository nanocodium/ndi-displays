package dev.nano.ndidisplays.hoist;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Outcome of a {@link StructureScanner} pass: either a complete, isolated load, or a
 * reason it cannot be flown. There is deliberately no third case — a partial capture is
 * how you end up sawing a building in half.
 */
public record ScanResult(Failure failure, Set<BlockPos> blocks, Set<BlockPos> motors) {

    public enum Failure {
        NONE("none"),
        /** Nothing under the hook. */
        EMPTY("empty"),
        /** The hook is on terrain, a hoist, or something else that never flies. */
        NOT_LIFTABLE("not_liftable"),
        /** More blocks than the configured cap. */
        TOO_MANY_BLOCKS("too_many_blocks"),
        /** Larger than the configured bounding box. */
        TOO_LARGE("too_large"),
        /**
         * The load is welded sideways to something that is staying put. Resting on the
         * floor is fine; a truss bolted to a wall is not a flown load.
         */
        NOT_ISOLATED("not_isolated"),
        /** Part of the structure is in an unloaded chunk. */
        UNLOADED("unloaded"),
        /** More motors on one structure than the configuration allows. */
        TOO_MANY_MOTORS("too_many_motors"),
        /**
         * The load is being held at an angle. Not a fault — blocks simply do not exist on
         * a slope, so a tilted rig has to come back to level before it can be set down.
         */
        TILTED("tilted");

        private final String key;

        Failure(String key) {
            this.key = key;
        }

        public String translationKey() {
            return "gui.ndidisplays.hoist.scan." + key;
        }
    }

    public boolean ok() {
        return failure == Failure.NONE;
    }

    public static ScanResult failed(Failure failure) {
        return new ScanResult(failure, Set.of(), Set.of());
    }

    public static ScanResult success(Set<BlockPos> blocks, Set<BlockPos> motors) {
        return new ScanResult(Failure.NONE, blocks, motors);
    }
}
