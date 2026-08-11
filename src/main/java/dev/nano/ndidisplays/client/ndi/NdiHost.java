package dev.nano.ndidisplays.client.ndi;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.ClientConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Decides whether this client is the machine that puts the world's NDI sources on the network.
 *
 * Receiving is always allowed — walls pull their streams themselves, which is correct and
 * scales. Sending is the part that must not be duplicated: on a server every client would
 * otherwise publish its own copy of every camera (machine-prefixed, so `PC1 (MC Cam …)` and
 * `PC2 (MC Cam …)`) and render each one separately. Real productions have one video server;
 * this is that role.
 */
public final class NdiHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean lastDecision;
    private static boolean decisionKnown;

    private NdiHost() {
    }

    /** Whether camera rigs and routers should broadcast from this client. */
    public static boolean shouldBroadcast() {
        boolean decision = decide();
        if (!decisionKnown || decision != lastDecision) {
            decisionKnown = true;
            lastDecision = decision;
            if (decision) {
                LOGGER.info("[ndidisplays] this client is the NDI broadcast host");
            } else {
                LOGGER.info("[ndidisplays] NDI broadcasting is off on this client"
                        + " (receiving still works). Set broadcast.mode = ALWAYS in"
                        + " config/ndidisplays-client.toml on the machine that should"
                        + " publish this world's cameras.");
            }
        }
        return decision;
    }

    /** The handheld is named per player, so it never duplicates and has its own switch. */
    public static boolean shouldBroadcastHandheld() {
        try {
            return ClientConfig.HANDHELD_BROADCAST.get();
        } catch (Throwable notLoadedYet) {
            return true;
        }
    }

    private static boolean decide() {
        ClientConfig.BroadcastMode mode;
        try {
            mode = ClientConfig.BROADCAST_MODE.get();
        } catch (Throwable notLoadedYet) {
            return false;
        }
        return switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            // Singleplayer and LAN hosting have exactly one client, so broadcasting is
            // unambiguous. Joining someone else's server stays quiet until opted in.
            case AUTO -> Minecraft.getInstance().hasSingleplayerServer();
        };
    }
}
