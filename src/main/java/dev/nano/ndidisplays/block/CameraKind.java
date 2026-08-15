package dev.nano.ndidisplays.block;

/** The four camera rigs. Determines default aim, motion behaviour and model. */
public enum CameraKind {
    /** Tripod ENG/box camera: static, aimed with pan/tilt trim + zoom. */
    BROADCAST,
    /** Single-arm broadcast PTZ: motorized pan/tilt that eases to its target at a slew rate. */
    PTZ,
    /** Boom arm on a pedestal, auto-sweeping over the stage; camera rides the tip. */
    JIB,
    /** Dolly that ping-pongs along a run of camera track blocks. */
    TRACK
}
