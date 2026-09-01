package dev.nano.ndidisplays.client.ndi;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.block.RackBlockEntity;
import dev.nano.ndidisplays.block.RackUnitType;
import me.walkerknapp.devolay.DevolayRouter;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rack-mounted routers: same NDI forwarding as {@link RouterManager}'s standalone blocks —
 * a stable published name, repatched to whatever source the operator selects, forwarded by NDI
 * itself with no pixels touched — but living in a rack slot and subject to rack power. Breaker
 * off, router off the network; exactly what pulling the power does to real rackmount gear.
 */
public final class RackRouters {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class Entry {
        RackBlockEntity rack;
        final int slot;
        DevolayRouter router;
        String publishedName;
        String patchedSource;
        boolean live;

        Entry(RackBlockEntity rack, int slot) {
            this.rack = rack;
            this.slot = slot;
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
                live = false;
            }
        }
    }

    /** Keyed by the rack's synthetic per-slot position (already unique and collision-free). */
    private static final Map<net.minecraft.core.BlockPos, Entry> ROUTERS = new ConcurrentHashMap<>();

    private RackRouters() {
    }

    /** Renderer check-in: this rack is visible; its router slots should be live. */
    public static void note(RackBlockEntity rack) {
        for (int i = 0; i < RackBlockEntity.SLOTS; i++) {
            if (rack.unit(i) == RackUnitType.ROUTER) {
                final int slot = i;
                ROUTERS.compute(rack.webKey(i), (k, old) -> {
                    if (old != null) {
                        old.rack = rack;
                        return old;
                    }
                    return new Entry(rack, slot);
                });
            }
        }
    }

    public static void shutdownAll() {
        ROUTERS.values().forEach(Entry::close);
        ROUTERS.clear();
    }

    /** Client tick, alongside RouterManager.tick(): reconcile every rack router with its slot. */
    public static void tick() {
        if (ROUTERS.isEmpty() || !NdiManager.isAvailable()) {
            return;
        }
        if (!NdiHost.shouldBroadcast()) {
            ROUTERS.values().forEach(Entry::close);
            return;
        }
        Iterator<Map.Entry<net.minecraft.core.BlockPos, Entry>> it = ROUTERS.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            RackBlockEntity rack = entry.rack;
            if (rack.isRemoved() || rack.unit(entry.slot) != RackUnitType.ROUTER) {
                entry.close();
                it.remove();
                continue;
            }
            if (!rack.powered()) {
                entry.close(); // no rack power, no router on the network
                continue;
            }
            var cfg = rack.cfg(entry.slot);
            String name = cfg.getString("Name");
            if (name.isBlank()) {
                name = "MC Rack Router " + rack.getBlockPos().toShortString() + " U" + (entry.slot + 1);
            }
            String source = cfg.getString("Source");
            try {
                if (entry.router == null || !name.equals(entry.publishedName)) {
                    entry.close();
                    entry.router = new DevolayRouter(name);
                    entry.publishedName = name;
                    LOGGER.info("[ndidisplays] rack router '{}' online", name);
                }
                if (!source.equals(entry.patchedSource) || !entry.live) {
                    entry.patchedSource = source;
                    boolean nowLive = !source.isBlank()
                            && NdiManager.routeTo(entry.router, source);
                    if (nowLive != entry.live) {
                        entry.live = nowLive;
                        LOGGER.info("[ndidisplays] rack router '{}' {}", name,
                                nowLive ? "patched to '" + source + "'" : "idle");
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[ndidisplays] rack router at {} U{} failed: {}",
                        rack.getBlockPos(), entry.slot + 1, t.toString());
                entry.close();
            }
        }
    }
}
