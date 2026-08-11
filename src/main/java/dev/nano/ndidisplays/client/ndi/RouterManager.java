package dev.nano.ndidisplays.client.ndi;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.block.NdiRouterBlockEntity;
import me.walkerknapp.devolay.DevolayRouter;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link DevolayRouter} per router block.
 *
 * A router publishes its own NDI name and forwards whichever source is patched to it —
 * NDI does the forwarding itself, with no decode or re-encode, so routers are effectively
 * free no matter the resolution. Repatching is what makes this useful: receivers stay
 * subscribed to a stable name like "MC PGM 1" while the operator changes what feeds it.
 *
 * Client-side, like the rest of the mod's NDI work. In multiplayer every client with the mod
 * would publish the same router name, so this is intended for a single operator's machine.
 */
public final class RouterManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class Entry {
        NdiRouterBlockEntity be;
        DevolayRouter router;
        String publishedName;
        String patchedSource;
        boolean live;
        boolean dirty = true;

        Entry(NdiRouterBlockEntity be) {
            this.be = be;
        }

        void close() {
            if (router != null) {
                try {
                    router.close();
                } catch (Throwable ignored) {
                }
                router = null;
                publishedName = null;
                patchedSource = null;
            }
        }
    }

    private static final Map<BlockPos, Entry> ROUTERS = new ConcurrentHashMap<>();

    private RouterManager() {
    }

    public static void register(NdiRouterBlockEntity be) {
        ROUTERS.compute(be.getBlockPos(), (pos, old) -> {
            if (old != null) {
                old.be = be;
                old.dirty = true;
                return old;
            }
            return new Entry(be);
        });
    }

    public static void unregister(NdiRouterBlockEntity be) {
        Entry entry = ROUTERS.get(be.getBlockPos());
        if (entry != null && entry.be == be) {
            ROUTERS.remove(be.getBlockPos());
            entry.close();
        }
    }

    /** Called when a config sync lands, so the repatch happens immediately. */
    public static void markDirty(BlockPos pos) {
        Entry entry = ROUTERS.get(pos);
        if (entry != null) {
            entry.dirty = true;
        }
    }

    /** Names of all live routers, so they can be picked as sources in the GUIs. */
    public static java.util.List<String> getRouterNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Entry entry : ROUTERS.values()) {
            if (entry.router != null && !entry.be.isRemoved()) {
                names.add(entry.be.getEffectiveOutputName());
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public static void shutdownAll() {
        ROUTERS.values().forEach(Entry::close);
        ROUTERS.clear();
    }

    /**
     * Client tick: create routers that do not exist yet, rename them when the operator
     * changes the output name, and repatch their source. Resolving the source only when
     * something changed (or every couple of seconds, so a source appearing late is picked
     * up) keeps this off the per-frame path.
     */
    public static void tick() {
        if (ROUTERS.isEmpty() || !NdiManager.isAvailable()) {
            return;
        }
        // A router publishes a name, so like rigs it must come from one machine only.
        if (!NdiHost.shouldBroadcast()) {
            ROUTERS.values().forEach(Entry::close);
            return;
        }
        for (Entry entry : ROUTERS.values()) {
            NdiRouterBlockEntity be = entry.be;
            if (be.isRemoved()) {
                entry.close();
                continue;
            }
            String wantedName = be.getEffectiveOutputName();
            String wantedSource = be.getSourceName();
            try {
                if (entry.router == null || !wantedName.equals(entry.publishedName)) {
                    entry.close();
                    entry.router = new DevolayRouter(wantedName);
                    entry.publishedName = wantedName;
                    entry.patchedSource = null;
                    entry.dirty = true;
                    LOGGER.info("[ndidisplays] NDI router '{}' online", wantedName);
                }
                boolean sourceChanged = !wantedSource.equals(entry.patchedSource);
                // Retry an unresolved patch periodically: the source may come online later.
                if (entry.dirty || sourceChanged || !entry.live) {
                    entry.dirty = false;
                    entry.patchedSource = wantedSource;
                    boolean nowLive = NdiManager.routeTo(entry.router, wantedSource);
                    if (nowLive != entry.live) {
                        entry.live = nowLive;
                        LOGGER.info("[ndidisplays] router '{}' {}", wantedName,
                                nowLive ? "patched to '" + wantedSource + "'" : "idle (no matching source)");
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[ndidisplays] router at {} failed: {}", be.getBlockPos(), t.toString());
                entry.close();
            }
        }
    }
}
