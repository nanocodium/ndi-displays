package dev.nano.ndidisplays;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client settings, chiefly which machine is responsible for putting this world's NDI sources
 * on the network.
 *
 * All NDI work in this mod is client-side, which is right for *receiving* (every viewer pulls
 * a stream itself, like every processor on a real stage network) but wrong for *sending*: on a
 * server every client would render and publish its own copy of every camera. Real productions
 * have one video server, so one machine takes the broadcast role.
 */
public final class ClientConfig {

    public enum BroadcastMode {
        /** Broadcast in singleplayer or when hosting; stay quiet as a client on a server. */
        AUTO,
        /** Always broadcast — the setting for the operator's machine on a server. */
        ALWAYS,
        /** Never broadcast; receive only. */
        NEVER
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<BroadcastMode> BROADCAST_MODE;
    public static final ForgeConfigSpec.BooleanValue HANDHELD_BROADCAST;
    public static final ForgeConfigSpec.IntValue CAMERA_RANGE;
    public static final ForgeConfigSpec.BooleanValue CAMERA_RANGE_UNLIMITED;
    public static final int CAMERA_RANGE_MIN = 16;
    public static final int CAMERA_RANGE_MAX = 1024;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_CLIMB;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_DESCEND;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_EXIT;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_WAYPOINT;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_MENU;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_PATH_PLAY;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_PATH_STOP;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_MOVE_STICK;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_LOOK_STICK;
    public static final ForgeConfigSpec.ConfigValue<String> DRONE_PAD_GUID;
    public static final ForgeConfigSpec.BooleanValue DRONE_PAD_INVERT_LOOK_Y;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_RECORD;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_ESTOP;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_MENU;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_PLAY;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_STOP;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_MOVE_STICK;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_LOOK_STICK;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_ZOOM_IN;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_ZOOM_OUT;
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_CAM_PAD_GUID;
    public static final ForgeConfigSpec.BooleanValue ACTIVE_CAM_PAD_INVERT_LOOK_Y;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("NDI broadcasting (camera rigs and routers).",
                        "On a multiplayer server exactly ONE machine should broadcast, or every",
                        "client publishes a duplicate copy of every camera and renders it again.",
                        "Set this to ALWAYS on the operator's machine and leave everyone else on AUTO.")
                .push("broadcast");
        BROADCAST_MODE = builder
                .comment("AUTO: broadcast only in singleplayer/LAN host. ALWAYS: always broadcast.",
                        "NEVER: receive only.")
                .defineEnum("mode", BroadcastMode.AUTO);
        HANDHELD_BROADCAST = builder
                .comment("Broadcast the handheld camera while it is held.",
                        "Unlike rigs this is named per player, so several players can each carry",
                        "one without clashing — handy for roving operators.")
                .define("handheld", true);
        CAMERA_RANGE = builder
                .comment("Distance in blocks from the player beyond which a camera stops sending.",
                        "A rig further away than this renders nothing and its NDI source goes idle;",
                        "it resumes as soon as the player is back in range. Cameras can only see",
                        "sections the player's own render distance has loaded, so a value far past",
                        "that just films fog.")
                .defineInRange("cameraRange", 96, CAMERA_RANGE_MIN, CAMERA_RANGE_MAX);
        CAMERA_RANGE_UNLIMITED = builder
                .comment("Ignore cameraRange: every active camera in the loaded world keeps sending.")
                .define("cameraRangeUnlimited", false);
        builder.pop();

        builder.comment("Drone gamepad bindings. Values are button:N, axis:N, or unbound.",
                        "Sticks are raw axis pairs (x,y) from the calibration wizard, or left/right.")
                .push("drone_pad");
        DRONE_PAD_CLIMB = builder.define("climb", "button:0+axis:5");
        DRONE_PAD_DESCEND = builder.define("descend", "axis:4");
        DRONE_PAD_EXIT = builder.define("exit", "button:1+button:6");
        DRONE_PAD_WAYPOINT = builder.define("waypoint", "button:3");
        DRONE_PAD_MENU = builder.define("menu", "button:7");
        DRONE_PAD_PATH_PLAY = builder.define("pathPlay", "unbound");
        DRONE_PAD_PATH_STOP = builder.define("pathStop", "unbound");
        DRONE_PAD_MOVE_STICK = builder.define("moveStick", "left");
        DRONE_PAD_LOOK_STICK = builder.define("lookStick", "right");
        DRONE_PAD_GUID = builder.define("joystickGuid", "");
        DRONE_PAD_INVERT_LOOK_Y = builder.define("invertLookY", false);
        builder.pop();

        builder.comment("Active Cam / Spidercam gamepad. Separate from drone_pad so both can be bound.")
                .push("active_cam_pad");
        ACTIVE_CAM_PAD_RECORD = builder.define("record", "button:0");
        ACTIVE_CAM_PAD_ESTOP = builder.define("estop", "button:1");
        ACTIVE_CAM_PAD_MENU = builder.define("menu", "button:7");
        ACTIVE_CAM_PAD_PLAY = builder.define("pathPlay", "unbound");
        ACTIVE_CAM_PAD_STOP = builder.define("pathStop", "unbound");
        ACTIVE_CAM_PAD_MOVE_STICK = builder.define("moveStick", "left");
        ACTIVE_CAM_PAD_LOOK_STICK = builder.define("lookStick", "right");
        ACTIVE_CAM_PAD_ZOOM_IN = builder.define("zoomIn", "axis:5");
        ACTIVE_CAM_PAD_ZOOM_OUT = builder.define("zoomOut", "axis:4");
        ACTIVE_CAM_PAD_GUID = builder.define("joystickGuid", "");
        ACTIVE_CAM_PAD_INVERT_LOOK_Y = builder.define("invertLookY", false);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
