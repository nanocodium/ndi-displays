package dev.nano.ndidisplays.activecam;

public enum ActiveCamMode {
    TWO_D,
    THREE_D;

    public static ActiveCamMode fromOrdinal(int ordinal) {
        ActiveCamMode[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return TWO_D;
        }
        return values[ordinal];
    }
}
