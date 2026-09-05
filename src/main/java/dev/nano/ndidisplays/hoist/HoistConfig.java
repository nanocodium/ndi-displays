package dev.nano.ndidisplays.hoist;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server-side limits for chain hoists.
 *
 * These exist to stop a hoist becoming a world-editing tool. A flown structure is a
 * truss grid with lights and speakers on it, not a building, so the caps are generous
 * for rigging and mean for anything else. Reaching a cap is a fault, never a partial
 * capture — see {@link StructureScanner}.
 */
public final class HoistConfig {

    public static final ForgeConfigSpec SPEC;

    /** Hard ceiling on a flown structure, blocks. */
    public static final ForgeConfigSpec.IntValue MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue MAX_SIZE_X;
    public static final ForgeConfigSpec.IntValue MAX_SIZE_Y;
    public static final ForgeConfigSpec.IntValue MAX_SIZE_Z;
    /** Longest chain a single hoist can pay out, metres. */
    public static final ForgeConfigSpec.DoubleValue MAX_CHAIN_LENGTH;
    /** Working speed of a fresh hoist, m/s. A CM Lodestar runs around 0.08–0.16 m/s. */
    public static final ForgeConfigSpec.DoubleValue DEFAULT_SPEED;
    public static final ForgeConfigSpec.DoubleValue MAX_SPEED;
    /** Steepest slope a multi-point rig may be held at, degrees from level. */
    public static final ForgeConfigSpec.DoubleValue MAX_TILT_DEGREES;
    /** Motors that may share one rig. */
    public static final ForgeConfigSpec.IntValue MAX_MOTORS_PER_RIG;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Chain hoist rigging limits.",
                        "A hoist flies the connected island of blocks hanging under its hook.",
                        "If that island is larger than these caps, or is joined to the world,",
                        "the hoist faults instead of capturing part of it.")
                .push("hoist");

        MAX_BLOCKS = builder
                .comment("Maximum blocks in one flown structure. Reaching this is a fault.")
                .defineInRange("maxBlocks", 256, 1, 4096);
        MAX_SIZE_X = builder
                .comment("Maximum flown structure size on X, blocks.")
                .defineInRange("maxSizeX", 32, 1, 128);
        MAX_SIZE_Y = builder
                .comment("Maximum flown structure size on Y, blocks.")
                .defineInRange("maxSizeY", 48, 1, 128);
        MAX_SIZE_Z = builder
                .comment("Maximum flown structure size on Z, blocks.")
                .defineInRange("maxSizeZ", 32, 1, 128);
        MAX_CHAIN_LENGTH = builder
                .comment("Longest chain one hoist can pay out, metres (blocks).")
                .defineInRange("maxChainLength", 32.0, 1.0, 256.0);
        DEFAULT_SPEED = builder
                .comment("Working speed of a newly placed hoist, m/s.")
                .defineInRange("defaultSpeed", 0.12, 0.01, 8.0);
        MAX_SPEED = builder
                .comment("Fastest speed selectable in the hoist GUI, m/s.")
                .defineInRange("maxSpeed", 1.0, 0.01, 8.0);
        MAX_TILT_DEGREES = builder
                .comment("Steepest slope a rig on several motors may be held at, degrees.",
                        "Each motor runs its own chain, so raising one corner tilts the structure.",
                        "A motor that would push the rig past this angle stops instead.",
                        "A tilted load stays in the air: blocks cannot be stored on a slope,",
                        "so it has to come back to level before it can be set down.")
                .defineInRange("maxTiltDegrees", 35.0, 0.0, 80.0);
        MAX_MOTORS_PER_RIG = builder
                .comment("Motors allowed on one flown structure.")
                .defineInRange("maxMotorsPerRig", 8, 1, 64);

        builder.pop();
        SPEC = builder.build();
    }

    private HoistConfig() {
    }

    public static int maxBlocks() {
        return MAX_BLOCKS.get();
    }

    public static float maxChainLength() {
        return MAX_CHAIN_LENGTH.get().floatValue();
    }

    public static float defaultSpeed() {
        return DEFAULT_SPEED.get().floatValue();
    }

    public static float maxSpeed() {
        return MAX_SPEED.get().floatValue();
    }

    public static float maxTiltDegrees() {
        return MAX_TILT_DEGREES.get().floatValue();
    }

    public static int maxMotors() {
        return MAX_MOTORS_PER_RIG.get();
    }
}
