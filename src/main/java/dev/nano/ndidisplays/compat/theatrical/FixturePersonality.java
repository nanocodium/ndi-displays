package dev.nano.ndidisplays.compat.theatrical;

/**
 * One DMX mode a real fixture declares, flattened into vanilla types so nothing outside
 * this package touches a Theatrical class.
 *
 * Theatrical fixtures publish their modes as {@code DMXPersonality} — a channel count, a
 * human description ("7ch - Standard", "10ch - Extended") and an ordered list of slots.
 * Reading them beats inventing our own modes: the winch then offers exactly the modes the
 * flown fixture really has, named as the fixture names them, and interprets each channel by
 * what its slot says it does rather than by a fixed position.
 *
 * @param description the fixture's own name for the mode, shown in the GUI
 * @param channelCount channels the fixture itself occupies, excluding the winch's own
 * @param slots        what each of those channels controls, as {@code SLOT_*} below
 */
public record FixturePersonality(String description, int channelCount, int[] slots) {

    /** A channel this mod has no meaning for; consumed and ignored. */
    public static final int SLOT_UNKNOWN = 0;
    public static final int SLOT_INTENSITY = 1;
    public static final int SLOT_RED = 2;
    public static final int SLOT_GREEN = 3;
    public static final int SLOT_BLUE = 4;
    public static final int SLOT_FOCUS = 5;
    public static final int SLOT_PAN = 6;
    public static final int SLOT_TILT = 7;

    /**
     * Channel index (0-based, within the fixture's own block of channels) carrying
     * {@code slot}, or -1 when this mode does not include it.
     */
    public int indexOf(int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }
}
