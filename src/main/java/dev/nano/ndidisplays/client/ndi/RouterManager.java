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

        // Generating mode. A router either forwards someone else's stream (DevolayRouter, no
        // pixels involved) or produces its own (a real sender). They are mutually exclusive, and
        // switching modes tears the other one down — two things publishing the same name would
        // fight over it on the network.
        me.walkerknapp.devolay.DevolaySender sender;
        java.nio.ByteBuffer frameBuffer;
        int frameWidth;
        int frameHeight;
        long frameCount;
        double nextFrameDue;
        int generatedPattern = -1;

        Entry(NdiRouterBlockEntity be) {
            this.be = be;
        }

        void closeSender() {
            if (sender != null) {
                try {
                    sender.close();
                } catch (Throwable ignored) {
                }
                sender = null;
            }
            if (frameBuffer != null) {
                org.lwjgl.system.MemoryUtil.memFree(frameBuffer);
                frameBuffer = null;
            }
            frameWidth = 0;
            frameHeight = 0;
            generatedPattern = -1;
        }

        void close() {
            closeSender();
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
     * Drops only routers registered against a different level (or removed): the
     * level-change cleanup. See CameraFeedManager.purgeStale — a blanket shutdown here
     * raced the same-tick onLoad registrations on world join and killed the routers
     * that had just come online.
     */
    public static void purgeStale(net.minecraft.client.multiplayer.ClientLevel level) {
        ROUTERS.entrySet().removeIf(entry -> {
            NdiRouterBlockEntity be = entry.getValue().be;
            if (be.isRemoved() || be.getLevel() != level) {
                entry.getValue().close();
                return true;
            }
            return false;
        });
    }

    /**
     * Client tick: create routers that do not exist yet, rename them when the operator
     * changes the output name, and repatch their source. Resolving the source only when
     * something changed (or every couple of seconds, so a source appearing late is picked
     * up) keeps this off the per-frame path.
     */
    /** Frames per second for generated patterns. Only the motion pattern actually changes. */
    private static final int PATTERN_FPS = 15;

    /**
     * Publishes one frame of the router's test pattern, creating the sender on demand.
     *
     * The pattern is built on the CPU by {@link TestPatternGenerator} and pushed straight out, so
     * it works with no world rendered and shares nothing with the GPU capture path — which is the
     * point of a test pattern: it has to be trustworthy when the thing you are testing is not.
     */
    private static void generate(Entry entry, String wantedName) {
        NdiRouterBlockEntity be = entry.be;
        double now = org.lwjgl.glfw.GLFW.glfwGetTime();
        int width = 1280;
        int height = 720;

        if (entry.sender == null || !wantedName.equals(entry.publishedName)) {
            entry.closeSender();
            // Unclocked, like the camera senders: we pace frames ourselves rather than letting
            // the sender block the calling thread to hit a declared rate.
            entry.sender = new me.walkerknapp.devolay.DevolaySender(wantedName, null, false, false);
            entry.publishedName = wantedName;
            entry.patchedSource = null;
            LOGGER.info("[ndidisplays] NDI router '{}' generating {}", wantedName,
                    TestPatternGenerator.patternName(be.getPattern()));
        }
        if (now < entry.nextFrameDue) {
            return;
        }
        double period = 1.0 / PATTERN_FPS;
        entry.nextFrameDue += period;
        if (entry.nextFrameDue < now - period) {
            entry.nextFrameDue = now + period;
        }

        int size = width * height * 4;
        if (entry.frameBuffer == null || entry.frameWidth != width || entry.frameHeight != height) {
            if (entry.frameBuffer != null) {
                org.lwjgl.system.MemoryUtil.memFree(entry.frameBuffer);
            }
            entry.frameBuffer = org.lwjgl.system.MemoryUtil.memAlloc(size);
            entry.frameWidth = width;
            entry.frameHeight = height;
        }
        if (entry.generatedPattern != be.getPattern()) {
            entry.generatedPattern = be.getPattern();
            LOGGER.info("[ndidisplays] router '{}' pattern is now {}", wantedName,
                    TestPatternGenerator.patternName(be.getPattern()));
        }

        TestPatternGenerator.render(entry.frameBuffer, width, height, be.getPattern(),
                wantedName, PATTERN_FPS, entry.frameCount++);

        try (me.walkerknapp.devolay.DevolayVideoFrame frame =
                     new me.walkerknapp.devolay.DevolayVideoFrame()) {
            frame.setResolution(width, height);
            frame.setFourCCType(me.walkerknapp.devolay.DevolayFrameFourCCType.BGRX);
            frame.setLineStride(width * 4);
            frame.setFrameRate(PATTERN_FPS, 1);
            frame.setData(entry.frameBuffer);
            entry.sender.sendVideoFrameAsync(frame);
        }
        entry.live = true;
    }

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
                if (be.isGenerating()) {
                    // Generating: no DevolayRouter, because there is nothing to forward.
                    if (entry.router != null) {
                        entry.close();
                    }
                    generate(entry, wantedName);
                    continue;
                }
                // Back to forwarding: drop the generator before the router claims the name.
                if (entry.sender != null) {
                    entry.closeSender();
                }
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
