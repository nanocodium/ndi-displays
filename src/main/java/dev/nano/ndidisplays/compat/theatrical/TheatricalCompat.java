package dev.nano.ndidisplays.compat.theatrical;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * Safe gate for the optional Theatrical integration. This class never references a
 * Theatrical type, so it can load anywhere; everything that does lives in
 * {@link TheatricalHooks} / {@link TheatricalClientHooks}, which are only classloaded
 * behind the {@link #LOADED} check.
 *
 * Theatrical's DMX API has moved between alpha builds (dmx.DMXNetworkData →
 * networks.TheatricalNetworkData), so every call is also guarded against linkage
 * errors: an installed Theatrical whose API doesn't match what this mod was compiled
 * against turns the integration off with one log line instead of crashing the server.
 */
public final class TheatricalCompat {

    public static final boolean LOADED = ModList.get().isLoaded("theatrical");

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean broken;

    private TheatricalCompat() {
    }

    private static boolean active() {
        return LOADED && !broken;
    }

    private static void markBroken(LinkageError e) {
        broken = true;
        LOGGER.error("[ndidisplays] Installed Theatrical version has an incompatible DMX API; "
                + "kinetic winch DMX control is disabled (GUI control still works). "
                + "Rebuild ndi-displays against this Theatrical version to re-enable it.", e);
    }

    /** Registers the winch as a DMX consumer on its Theatrical network (server side). */
    public static void register(KineticWinchBlockEntity be) {
        if (!active()) {
            return;
        }
        try {
            TheatricalHooks.register(be);
        } catch (LinkageError e) {
            markBroken(e);
        }
    }

    /** Removes the winch from its Theatrical network (server side). */
    public static void unregister(KineticWinchBlockEntity be) {
        if (!active()) {
            return;
        }
        try {
            TheatricalHooks.unregister(be);
        } catch (LinkageError e) {
            markBroken(e);
        }
    }

    /** True when the block is a Theatrical/Extra Lights fixture a winch can fly. */
    public static boolean isFixtureBlock(net.minecraft.world.level.block.Block block) {
        if (!active()) {
            return false;
        }
        try {
            return TheatricalHooks.isFixtureBlock(block);
        } catch (LinkageError e) {
            markBroken(e);
            return false;
        }
    }

    /** Registers a fixed screen (wall panel / round / curved) as a 2ch DMX consumer. */
    public static void registerScreen(dev.nano.ndidisplays.block.DmxScreen screen) {
        if (!active()) {
            return;
        }
        try {
            TheatricalHooks.registerScreen(screen);
        } catch (LinkageError e) {
            markBroken(e);
        }
    }

    /** Removes a fixed screen from its Theatrical network (server side). */
    public static void unregisterScreen(dev.nano.ndidisplays.block.DmxScreen screen) {
        if (!active()) {
            return;
        }
        try {
            TheatricalHooks.unregisterScreen(screen);
        } catch (LinkageError e) {
            markBroken(e);
        }
    }

    private static final Map<String, java.util.Optional<FixtureModelData>> FIXTURE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Model locations and pivots of a Theatrical fixture block, for the flown-fixture
     * renderer. Client side; null when Theatrical is absent or the id isn't a fixture.
     * Cached per block id — the data is static per fixture type.
     */
    /**
     * Submits a flown-fixture beam into Theatrical's volumetric raymarch pipeline.
     * Returns false when it isn't available and the caller should draw a classic
     * beam cone itself. Client side.
     */
    public static boolean submitFixtureBeam(net.minecraft.core.BlockPos fixturePos,
                                            org.joml.Matrix4f headMatrix, float beamWidth,
                                            float focus01, float r, float g, float b,
                                            float intensity01, float length) {
        if (!active()) {
            return false;
        }
        try {
            return TheatricalBeamHooks.submitBeam(fixturePos, headMatrix, beamWidth,
                    focus01, r, g, b, intensity01, length);
        } catch (LinkageError e) {
            markBroken(e);
            return false;
        }
    }

    @javax.annotation.Nullable
    public static FixtureModelData fixtureModelData(String blockId) {
        if (!active() || blockId.isEmpty()) {
            return null;
        }
        try {
            return FIXTURE_CACHE.computeIfAbsent(blockId, id ->
                    java.util.Optional.ofNullable(TheatricalFixtureHooks.resolve(id))).orElse(null);
        } catch (LinkageError e) {
            markBroken(e);
            return null;
        }
    }

    private static final Map<String, java.util.List<FixturePersonality>> PERSONALITY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The DMX modes the flown fixture really declares, so the winch offers the fixture's own
     * modes instead of invented ones. Empty when Theatrical is absent or the block is not a
     * fixture, in which case the winch falls back to its generic footprints.
     */
    public static java.util.List<FixturePersonality> fixturePersonalities(String blockId) {
        if (!active() || blockId == null || blockId.isEmpty()) {
            return java.util.List.of();
        }
        try {
            return PERSONALITY_CACHE.computeIfAbsent(blockId, TheatricalFixtureHooks::personalities);
        } catch (LinkageError e) {
            markBroken(e);
            return java.util.List.of();
        }
    }

    /**
     * Known Theatrical networks (id → display name), for the config screen's network
     * picker. Client side; empty when Theatrical is absent.
     */
    public static Map<UUID, String> knownNetworks() {
        if (!active()) {
            return Map.of();
        }
        try {
            return TheatricalClientHooks.knownNetworks();
        } catch (LinkageError e) {
            markBroken(e);
            return Map.of();
        }
    }
}
