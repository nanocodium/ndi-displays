package dev.nano.ndidisplays.activecam;

public enum ActiveCamHeadMode {
    RECORDED,
    TRACK_TARGET;

    public static ActiveCamHeadMode fromOrdinal(int ordinal) {
        ActiveCamHeadMode[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return RECORDED;
        }
        return values[ordinal];
    }
}
