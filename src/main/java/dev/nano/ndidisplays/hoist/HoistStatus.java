package dev.nano.ndidisplays.hoist;

/**
 * What a chain hoist is doing, mirroring the lamp set on a real motor controller.
 *
 * Everything that goes wrong resolves to a stopped state rather than a destructive one:
 * a hoist that cannot safely move stops and says why, it never pushes the load through
 * an obstruction and never deletes blocks.
 */
public enum HoistStatus {

    /** Idle. Chain may be attached to a load or hanging free. */
    STOPPED("stopped", 0xFFB0B4BA),
    /** Taking chain in; the load is rising. */
    MOVING_UP("moving_up", 0xFF4EE26C),
    /** Paying chain out; the load is descending. */
    MOVING_DOWN("moving_down", 0xFF4EA8E2),
    /** At the shortest configured chain length — cannot go higher. */
    UPPER_LIMIT("upper_limit", 0xFFE2C34E),
    /** At the longest configured chain length — cannot go lower. */
    LOWER_LIMIT("lower_limit", 0xFFE2C34E),
    /**
     * Something in the travel path. The load is held where it is; clearing the
     * obstruction and pressing the direction again resumes.
     */
    OBSTRUCTED("obstructed", 0xFFE28A2E),
    /**
     * The rig itself is not valid: the structure could not be captured, it is welded
     * to the world, it is over the size limit, or the motors disagree.
     */
    FAULT("fault", 0xFFE24E4E);

    private final String key;
    private final int colour;

    HoistStatus(String key, int colour) {
        this.key = key;
        this.colour = colour;
    }

    public String translationKey() {
        return "gui.ndidisplays.hoist.status." + key;
    }

    /** ARGB for the GUI lamp and status text. */
    public int colour() {
        return colour;
    }

    public boolean isMoving() {
        return this == MOVING_UP || this == MOVING_DOWN;
    }

    /** A fault or obstruction: the operator has to act before anything else happens. */
    public boolean isBlocking() {
        return this == FAULT || this == OBSTRUCTED;
    }

    public static HoistStatus byOrdinal(int ordinal) {
        HoistStatus[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : STOPPED;
    }
}
