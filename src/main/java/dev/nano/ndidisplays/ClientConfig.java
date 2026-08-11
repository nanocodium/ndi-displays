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
        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
