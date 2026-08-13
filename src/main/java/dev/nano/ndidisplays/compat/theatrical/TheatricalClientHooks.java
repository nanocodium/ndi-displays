package dev.nano.ndidisplays.compat.theatrical;

import dev.imabad.theatrical.TheatricalClient;

import java.util.Map;
import java.util.UUID;

/**
 * Client-side Theatrical access, split from {@link TheatricalHooks} so a dedicated
 * server never classloads Theatrical's client classes.
 */
final class TheatricalClientHooks {

    private TheatricalClientHooks() {
    }

    /** Networks the client has been told about (pushed on join, refreshed by Theatrical). */
    static Map<UUID, String> knownNetworks() {
        return TheatricalClient.getArtNetManager().getKnownNetworks();
    }
}
