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
        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
