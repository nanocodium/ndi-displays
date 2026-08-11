package dev.nano.ndidisplays.client;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.block.CameraKind;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateCameraConfigPacket;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.walkerknapp.devolay.DevolayFrameFourCCType;
import me.walkerknapp.devolay.DevolayFrameType;
import me.walkerknapp.devolay.DevolayMetadataFrame;
import me.walkerknapp.devolay.DevolaySender;
import me.walkerknapp.devolay.DevolayVideoFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders each active camera rig's viewpoint into an off-screen target and
 * broadcasts it as a real NDI video source.
 *
 * The world capture uses the same approach as SecurityCraft's frame feeds:
 * swap the main render target, point a dummy camera entity at the desired
 * view, call {@code gameRenderer.renderLevel}, restore everything. On top of
 * that, the LevelRenderer's chunk-grid centre fields are pinned during the
 * capture so the view area is never re-centred on the camera — this avoids
 * chunk rebuild storms, at the cost of cameras only seeing what is inside the
 * player's built chunk area (fine for stage cameras near the action).
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, value = Dist.CLIENT)
public final class CameraFeedManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double MAX_CAMERA_DISTANCE = 96.0;

    /** -Dndidisplays.debugCapture=true logs each rig's real capture geometry once. */
    private static final boolean DEBUG_GEOMETRY =
            Boolean.getBoolean("ndidisplays.debugCapture");

    /**
     * Hard override for rig captures per game frame; when set, adaptive budgeting is off.
     * Each capture is one extra world render, so this is a direct game-fps vs feed-fps trade.
     */
    private static final Integer CAPTURES_PER_FRAME_OVERRIDE =
            Integer.getInteger("ndidisplays.capturesPerFrame");
    /** Ceiling for the adaptive budget. */
    private static final int MAX_CAPTURES_PER_FRAME =
            Math.max(1, Integer.getInteger("ndidisplays.maxCapturesPerFrame", 4));
    /** The game framerate the adaptive budget protects. */
    private static final double TARGET_GAME_FPS =
            Double.parseDouble(System.getProperty("ndidisplays.targetGameFps", "50"));
    /** -Dndidisplays.perfLog=true reports capture cost and delivered feed rates. */
    private static final boolean PERF_LOG = Boolean.getBoolean("ndidisplays.perfLog");

    /**
     * Fraction of wall-clock time captures may consume in total. This is the game-fps vs
     * feed-fps dial: 0.5 means captures get at most half of real time, leaving the rest for
     * the player's own frames. Raise with -Dndidisplays.captureLoad if feeds matter more.
     */
    private static final double CAPTURE_LOAD =
            Double.parseDouble(System.getProperty("ndidisplays.captureLoad", "0.5"));
    /** Never starve a watched feed below this, even with many cameras live. */
    private static final double MIN_FEED_FPS = 5.0;

    /** Smoothed cost of one capture, in ms; drives the honest frame-rate cap below. */
    private static double captureMsAvg = 5.0;
    /** Watched feeds seen on the last pass, for dividing capture capacity between them. */
    private static int watchedCount = 1;

    /**
     * The frame rate a single watched feed can actually be given right now.
     *
     * Declaring a rate we cannot sustain is what makes NDI receivers grade a source as
     * unreliable: seven cameras each promising 30 fps need 210 captures a second, and when
     * the renders cannot keep up every frame arrives late. Dividing the measured capture
     * capacity between the watched feeds instead means each one promises what it delivers,
     * which reads as a clean, smooth source — just at a lower rate.
     *
     * Derived from measured cost rather than achieved throughput on purpose: throughput
     * would feed back on itself and spiral down to the floor.
     */
    private static double sustainableFeedFps() {
        double capturesPerSecond = 1000.0 * CAPTURE_LOAD / Math.max(captureMsAvg, 0.1);
        return Math.max(MIN_FEED_FPS, capturesPerSecond / Math.max(1, watchedCount));
    }

    /** The rate this feed is both paced at and advertised as. */
    private static int effectiveFps(NdiCameraBlockEntity be) {
        return Math.max(1, (int) Math.round(Math.min(be.getFps(), sustainableFeedFps())));
    }

    // Adaptive capture budget: spend spare frame time on feed framerate, back off when
    // the game's own framerate is suffering.
    private static int adaptiveBudget = 1;
    private static double frameTimeAvg = 1.0 / 60.0;
    private static double lastTickTime;
    private static int budgetCooldown;
    // Perf telemetry
    private static int capturesThisSecond;
    private static double perfWindowStart;

    private static final Map<BlockPos, Feed> FEEDS = new ConcurrentHashMap<>();

    /**
     * One capture target per output resolution, keyed by packed width/height. Rigs can run
     * at different resolutions and captures round-robin between them, so a single shared
     * target would be destroyed and reallocated on every single captured frame. Bounded by
     * the number of resolution presets a rig can select.
     */
    private static final Map<Long, RenderTarget> CAPTURE_TARGETS = new HashMap<>();

    /** The target for the capture currently in progress; render thread only. */
    private static RenderTarget captureTarget;

    private static boolean capturing;
    private static double captureFov = 70.0;

    // Shader-mode pacing. Every shader-mode capture is a full second render of the
    // pack's pipeline plus a synchronous GPU readback, so it must never run at the
    // rig's configured fps: captures are globally capped, and suspended entirely for
    // a grace period after the pack toggles while it compiles its programs.
    private static final double SHADER_CAPTURE_MAX_FPS = 10.0;
    private static final double SHADER_TOGGLE_GRACE_SECONDS = 3.0;
    /**
     * Rig capture under a shader pack is EXPERIMENTAL and off by default: re-entering
     * the pack's pipeline for a second view poisons persistent pipeline state — measured
     * as both the capture and the player's own world render going fully black (avg
     * luminance 0/255) the moment captures start. Until a real Iris integration exists,
     * rigs cut to black while a pack is active; the handheld camera still streams the
     * player's shaded view. Opt back into the experiment with -Dndidisplays.shaderCapture=true.
     */
    private static final boolean SHADER_CAPTURE_EXPERIMENT =
            Boolean.getBoolean("ndidisplays.shaderCapture");
    private static boolean lastShadersActive;
    private static double shaderGraceUntil;
    private static double lastShaderCapture;
    private static boolean shaderModeLogged;
    private static double lastFeedLumLog;
    private static double lastScreenLumLog;

    private static final class Feed {
        NdiCameraBlockEntity be;
        DevolaySender sender;
        String senderName;
        double nextDue;
        int failures;
        int loggedGeometry;
        int framesCaptured;

        /**
         * This feed's own staging buffers, kept for the sender's whole lifetime.
         *
         * Two of them, alternating: frames are submitted with the ASYNC send, which
         * returns immediately and keeps reading the submitted buffer until the next
         * async call on the same sender — so the buffer being filled is never the one
         * NDI is still compressing. They must not be shared between feeds or resized
         * underneath a live sender; the sender is flushed (closed) before any resize.
         */
        final ByteBuffer[] sendBuffers = new ByteBuffer[2];
        int sendIndex;

        // --- NDI PTZ control state (PTZ rigs only) --------------------------
        /** Reused metadata frame for polling PTZ commands off this feed's sender. */
        DevolayMetadataFrame ptzMeta;
        /** Continuous-motion velocities from ntk_ptz_*_speed commands, -1..1. */
        float ptzPanSpeed;
        float ptzTiltSpeed;
        float ptzZoomSpeed;
        double ptzLastIntegrate;
        boolean ptzDirty;
        double ptzLastSent;
        /** Controller presets 0-9: {pan, tilt, fov}. Client-session only. */
        final float[][] ptzPresets = new float[10][];

        // --- async GPU readback --------------------------------------------
        /**
         * Pixel-pack buffer for this feed. glReadPixels into a PBO returns immediately
         * instead of stalling the render thread until the GPU finishes the capture
         * render; the pixels are collected on the feed's NEXT capture, by which point
         * the GPU has long finished. Costs one feed-frame of latency, removes the
         * biggest per-capture frame-time spike.
         */
        int pbo;
        int pboCapacity;
        boolean pboPending;
        int pboWidth;
        int pboHeight;
        int pboFps;
        boolean pboPtz;
        String pboName;

        // --- viewer gating ---------------------------------------------------
        /** Cached NDI receiver count; a rig with no viewers is not worth rendering. */
        int viewers;
        double viewersCheckedAt;
        /** Frames actually delivered in the current perf window (telemetry only). */
        int delivered;

        Feed(NdiCameraBlockEntity be) {
            this.be = be;
        }

        /**
         * The staging buffer for the NEXT frame, alternating between the pair. Sized
         * once per resolution; only ever reallocated while no sender is live (closing
         * the sender flushes any in-flight async frame first).
         */
        ByteBuffer stagingBuffer(int size) {
            sendIndex ^= 1;
            ByteBuffer buf = sendBuffers[sendIndex];
            if (buf == null || buf.capacity() < size) {
                // Only this slot is replaced, and no sender teardown is needed: an async
                // send retains just the most recently submitted buffer, which is the *other*
                // slot. This one was last submitted two sends ago, so NDI has finished with
                // it. Growing both slots (and closing the sender to be safe) used to drop
                // the source off the network for a moment on every resolution change.
                if (buf != null) {
                    MemoryUtil.memFree(buf);
                }
                buf = MemoryUtil.memAlloc(size);
                sendBuffers[sendIndex] = buf;
            }
            return buf;
        }

        void closeSender() {
            // The metadata frame's last buffer is owned by (and freed through) the sender,
            // so it must go first, while the sender is still alive.
            if (ptzMeta != null) {
                try {
                    ptzMeta.close();
                } catch (Throwable ignored) {
                }
                ptzMeta = null;
            }
            if (sender != null) {
                try {
                    sender.close();
                } catch (Throwable ignored) {
                }
                sender = null;
                senderName = null;
            }
        }

        /** Full teardown: the buffers are only safe to free once the sender is gone. */
        void release() {
            closeSender();
            for (int i = 0; i < sendBuffers.length; i++) {
                if (sendBuffers[i] != null) {
                    MemoryUtil.memFree(sendBuffers[i]);
                    sendBuffers[i] = null;
                }
            }
            if (pbo != 0) {
                org.lwjgl.opengl.GL15C.glDeleteBuffers(pbo);
                pbo = 0;
                pboPending = false;
            }
        }
    }

    private CameraFeedManager() {
    }

    public static void register(NdiCameraBlockEntity be) {
        FEEDS.compute(be.getBlockPos(), (pos, old) -> {
            if (old != null) {
                old.be = be;
                return old;
            }
            return new Feed(be);
        });
    }

    public static void unregister(NdiCameraBlockEntity be) {
        Feed feed = FEEDS.get(be.getBlockPos());
        if (feed != null && feed.be == be) {
            FEEDS.remove(be.getBlockPos());
            feed.release();
        }
    }

    public static boolean isCapturing() {
        return capturing;
    }

    /** Source names of all live in-game camera rigs, for direct selection in GUIs. */
    public static java.util.List<String> getLiveCameraNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Feed feed : FEEDS.values()) {
            NdiCameraBlockEntity be = feed.be;
            if (!be.isRemoved() && be.isActive()) {
                names.add(be.getEffectiveSourceName());
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /**
     * Releases everything the capture path owns. Called when leaving a world, so the
     * framebuffers and native staging buffers are not kept for the rest of the process
     * (and are rebuilt on demand if another world is joined).
     */
    public static void shutdownAll() {
        FEEDS.values().forEach(Feed::release);
        FEEDS.clear();
        if (handheldFeed != null) {
            handheldFeed.release();
            handheldFeed = null;
        }
        CAPTURE_TARGETS.values().forEach(RenderTarget::destroyBuffers);
        CAPTURE_TARGETS.clear();
        captureTarget = null;
    }

    /** Zoom for the capture pass: overrides the FOV while our view renders. */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (capturing) {
            event.setFOV(captureFov);
        }
    }

    /** Set for one probed capture per feed; the stage probe below logs the GL viewport. */
    private static boolean probeViewport;

    /**
     * Diagnostic: entities in captures render displaced by a constant screen-space offset
     * that tracks the real window size — the signature of something rebinding a
     * window-sized viewport mid-renderLevel. This logs the actual GL viewport at each
     * render stage of a probed capture to pinpoint the stage where it flips.
     */
    @SubscribeEvent
    public static void onRenderStage(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!capturing || !probeViewport) {
            return;
        }
        int[] vp = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
        LOGGER.info("[ndidisplays] stage {} viewport {},{} {}x{} boundDrawFbo={}",
                event.getStage(), vp[0], vp[1], vp[2], vp[3],
                GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !NdiManager.isAvailable()) {
            return;
        }

        // Capture timing depends on the pipeline. Vanilla mode captures AFTER the frame
        // (Phase.END) into an off-screen target. Shader mode cannot do that — the pack
        // owns the pipeline and its buffers — so it captures BEFORE the frame
        // (Phase.START): the rig view renders through the pack's full pipeline into the
        // real main target at window size, is read back, and the player's own frame then
        // renders over it. The player never sees the capture; the feed gets the pack's
        // full visuals.
        boolean shaders = dev.nano.ndidisplays.client.render.ShaderPackCompat.shaderPackActive();
        if (shaders != (event.phase == TickEvent.Phase.START)) {
            return;
        }

        // Only the broadcast host publishes rigs. Everyone else's walls still receive, so a
        // server full of players sees the same feeds without each machine rendering its own
        // duplicate copy of every camera.
        if (!dev.nano.ndidisplays.client.ndi.NdiHost.shouldBroadcast()) {
            if (!FEEDS.isEmpty()) {
                FEEDS.values().forEach(Feed::release);
                // Drop them too, or the released feeds are re-released every frame and
                // their names keep appearing as pickable sources that no longer exist.
                FEEDS.clear();
            }
            return;
        }

        double now = GLFW.glfwGetTime();
        if (shaders != lastShadersActive) {
            lastShadersActive = shaders;
            shaderGraceUntil = now + SHADER_TOGGLE_GRACE_SECONDS;
            shaderModeLogged = false;
            if (shaders && !SHADER_CAPTURE_EXPERIMENT) {
                // Rigs go cleanly off air instead of freezing on a stale frame.
                LOGGER.info("[ndidisplays] shader pack active — rig capture paused (handheld"
                        + " and NDI reception keep working)");
                for (Feed feed : FEEDS.values()) {
                    if (feed.sender != null) {
                        sendBlackFrame(feed);
                    }
                }
            }
        }
        if (shaders) {
            if (!SHADER_CAPTURE_EXPERIMENT || now < shaderGraceUntil
                    || now - lastShaderCapture < 1.0 / SHADER_CAPTURE_MAX_FPS) {
                return;
            }
            if (!shaderModeLogged) {
                shaderModeLogged = true;
                LOGGER.info("[ndidisplays] EXPERIMENTAL shader-mode capture active, throttled"
                        + " to {} fps total", (int) SHADER_CAPTURE_MAX_FPS);
            }
        }
        // The handheld camera keeps the one-capture-per-frame budget: when it captured
        // this frame, the block rigs wait for the next one. Under shaders the handheld
        // is served by the RenderGuiEvent screen copy instead.
        if (!shaders && tickHandheld(mc, player, now)) {
            return;
        }
        if (FEEDS.isEmpty()) {
            return;
        }

        // Frame-time tracking for the adaptive budget below.
        if (lastTickTime > 0.0) {
            double dt = now - lastTickTime;
            if (dt > 0.0 && dt < 0.5) {
                frameTimeAvg += (dt - frameTimeAvg) * 0.1;
            }
        }
        lastTickTime = now;

        // Housekeeping: PTZ polling, and bring every active in-range rig's sender online
        // so it is discoverable and can report viewers — without rendering anything yet.
        for (Feed feed : FEEDS.values()) {
            NdiCameraBlockEntity be = feed.be;
            if (be.isRemoved() || !be.isActive()) {
                feed.closeSender();
                continue;
            }
            pollPtzControl(feed, now);
            if (be.getBlockPos().distToCenterSqr(player.position())
                    <= MAX_CAMERA_DISTANCE * MAX_CAMERA_DISTANCE) {
                ensureSender(feed, be.getEffectiveSourceName(), be.getKind() == CameraKind.PTZ,
                        be.getWidth(), be.getHeight(), be.getFps());
            }
        }

        // Adaptive budget: each capture is one extra world render, so spend spare frame
        // time on feed framerate and back off when the game's own framerate suffers.
        // Shader mode stays at one — each of those is a full shader-pack render.
        if (--budgetCooldown <= 0) {
            budgetCooldown = 20;
            double target = 1.0 / TARGET_GAME_FPS;
            if (frameTimeAvg < target * 0.85 && adaptiveBudget < MAX_CAPTURES_PER_FRAME) {
                adaptiveBudget++;
            } else if (frameTimeAvg > target && adaptiveBudget > 1) {
                adaptiveBudget--;
            }
        }
        int budget = shaders ? 1
                : (CAPTURES_PER_FRAME_OVERRIDE != null
                        ? Math.max(1, CAPTURES_PER_FRAME_OVERRIDE) : adaptiveBudget);

        for (int slot = 0; slot < budget; slot++) {
            Feed due = null;
            int watched = 0;
            for (Feed feed : FEEDS.values()) {
                NdiCameraBlockEntity be = feed.be;
                if (be.isRemoved() || !be.isActive()) {
                    continue;
                }
                if (be.getBlockPos().distToCenterSqr(player.position()) > MAX_CAMERA_DISTANCE * MAX_CAMERA_DISTANCE) {
                    continue;
                }
                // The core of multi-camera performance: only rigs someone is subscribed to
                // cost a render. Placed-but-unwatched rigs are free.
                if (!hasViewers(feed, now)) {
                    feed.nextDue = now; // no burst when a viewer subscribes
                    continue;
                }
                watched++;
                if (now >= feed.nextDue && (due == null || feed.nextDue < due.nextDue)) {
                    due = feed;
                }
            }
            // Capture capacity is shared between watched feeds, so the per-feed rate cap
            // depends on how many there are.
            watchedCount = Math.max(1, watched);
            if (due == null) {
                break;
            }
            // Advance on the ideal cadence grid rather than from "now", so delivery is
            // evenly spaced — receivers judge jitter, not just average rate. Resync when
            // we have fallen more than a frame behind instead of bursting to catch up.
            double period = 1.0 / effectiveFps(due.be);
            due.nextDue += period;
            if (due.nextDue < now - period) {
                due.nextDue = now + period;
            }

            try {
                // Always timed: the measured cost is what the frame-rate cap is derived from.
                long t0 = System.nanoTime();
                if (shaders) {
                    lastShaderCapture = now;
                    captureAndSendShaderMode(mc, due);
                } else {
                    captureAndSend(mc, due);
                }
                due.failures = 0;
                due.delivered++;
                capturesThisSecond++;
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                captureMsAvg += (ms - captureMsAvg) * 0.1;
            } catch (Throwable t) {
                due.failures++;
                due.nextDue = now + 5.0; // back off, then retry
                if (due.failures == 1) {
                    LOGGER.error("[ndidisplays] camera capture failed at {} (will retry)", due.be.getBlockPos(), t);
                } else {
                    LOGGER.warn("[ndidisplays] camera capture failed at {} ({}x): {}",
                            due.be.getBlockPos(), due.failures, t.toString());
                }
                if (due.failures >= 20) {
                    LOGGER.error("[ndidisplays] camera at {} keeps failing, disabling feed", due.be.getBlockPos());
                    FEEDS.remove(due.be.getBlockPos());
                    due.release();
                }
            }
        }

        if (PERF_LOG && now - perfWindowStart >= 5.0) {
            double window = now - perfWindowStart;
            perfWindowStart = now;
            StringBuilder perFeed = new StringBuilder();
            for (Feed feed : FEEDS.values()) {
                if (feed.delivered > 0) {
                    perFeed.append(String.format("%s=%.1ffps(%dv) ",
                            feed.senderName, feed.delivered / window, feed.viewers));
                }
                feed.delivered = 0;
            }
            LOGGER.info("[ndidisplays] perf: game {} fps | capture {} ms | budget {}/frame"
                            + " | {} captures/s | cap {} fps/feed | {}",
                    String.format("%.0f", 1.0 / Math.max(frameTimeAvg, 1e-4)),
                    String.format("%.1f", captureMsAvg), budget,
                    String.format("%.0f", capturesThisSecond / window),
                    String.format("%.0f", sustainableFeedFps()),
                    perFeed.length() == 0 ? "no watched rigs" : perFeed.toString().trim());
            capturesThisSecond = 0;
        }
    }

    // --- handheld camera -----------------------------------------------------
    //
    // A camera item rather than a rig: while the local player holds it (either hand),
    // their own first-person view is broadcast as "MC Handheld <player>", with a touch
    // of operator wobble so it reads as a shoulder camera rather than a locked-off shot.

    private static final int HANDHELD_WIDTH = 1280;
    private static final int HANDHELD_HEIGHT = 720;
    private static final int HANDHELD_FPS = 30;
    /** Eye offset ahead of the player's face, so the operator's own head is not in shot. */
    private static final double HANDHELD_FORWARD = 0.40;

    private static Feed handheldFeed;
    private static double handheldDue;
    /**
     * When the handheld sender may be torn down. Scrolling the hotbar past the camera used
     * to destroy and recreate the NDI source on every flick, which drops receivers and spams
     * the log; a short grace period keeps the source alive across incidental switches.
     */
    private static double handheldKeepUntil;
    private static final double HANDHELD_GRACE_SECONDS = 5.0;

    private static boolean holdingHandheld(LocalPlayer player) {
        // Named per player, so it never duplicates across clients and has its own switch —
        // several roving operators can each carry one on a server.
        return dev.nano.ndidisplays.client.ndi.NdiHost.shouldBroadcastHandheld()
                && (player.getMainHandItem().is(NdiDisplays.HANDHELD_CAMERA_ITEM.get())
                    || player.getOffhandItem().is(NdiDisplays.HANDHELD_CAMERA_ITEM.get()));
    }

    /**
     * Shader-pack path for the handheld camera: no nested world render is possible under a
     * pack's pipeline, but the handheld's feed IS the player's view — so it copies the
     * already-composited frame (fully shaded, pre-HUD) straight off the main framebuffer.
     * The one camera that keeps broadcasting with shaders on, with the pack's visuals.
     */
    @SubscribeEvent
    public static void onRenderGui(net.minecraftforge.client.event.RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !NdiManager.isAvailable()
                || !dev.nano.ndidisplays.client.render.ShaderPackCompat.shaderPackActive()) {
            return;
        }

        // Diagnostic counterpart to the feed probe: the PLAYER's frame, sampled after the
        // world render and before the HUD. Together the two log lines pinpoint where a
        // black screen originates (capture, player frame, or the final window blit).
        double lumNow = GLFW.glfwGetTime();
        if (lumNow - lastShaderCapture < 1.0 && lumNow - lastScreenLumLog > 2.0) {
            lastScreenLumLog = lumNow;
            try {
                RenderTarget main = mc.getMainRenderTarget();
                int w = 32;
                int h = 32;
                ByteBuffer probe = MemoryUtil.memAlloc(w * h * 4);
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
                // All four pack parameters, not just alignment: a non-zero ROW_LENGTH or
                // SKIP_* inherited from other code would make glReadPixels write beyond
                // this allocation, corrupting the heap rather than just misreading.
                GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
                GL11C.glReadPixels(Math.max(0, main.viewWidth / 2 - w / 2),
                        Math.max(0, main.viewHeight / 2 - h / 2), w, h,
                        GL12C.GL_BGRA, GL11C.GL_UNSIGNED_BYTE, probe);
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, 0);
                long sum = 0;
                for (int i = 0; i < w * h * 4; i += 4) {
                    sum += (probe.get(i) & 0xFF) + (probe.get(i + 1) & 0xFF) + (probe.get(i + 2) & 0xFF);
                }
                MemoryUtil.memFree(probe);
                LOGGER.info("[ndidisplays] shader-mode PLAYER frame avg luminance {}/255",
                        sum / (w * h * 3));
            } catch (Throwable ignored) {
            }
        }
        if (!holdingHandheld(player)) {
            if (handheldFeed != null && GLFW.glfwGetTime() > handheldKeepUntil) {
                handheldFeed.release();
                handheldFeed = null;
            }
            return;
        }
        handheldKeepUntil = GLFW.glfwGetTime() + HANDHELD_GRACE_SECONDS;
        double now = GLFW.glfwGetTime();
        if (now < handheldDue) {
            return;
        }
        handheldDue = now + 1.0 / HANDHELD_FPS;
        if (handheldFeed == null) {
            handheldFeed = new Feed(null);
        }
        try {
            completePendingReadback(handheldFeed);
            RenderTarget main = mc.getMainRenderTarget();
            captureTarget = main;
            readAndSend(handheldFeed, "MC Handheld " + player.getGameProfile().getName(),
                    HANDHELD_FPS, false, main.viewWidth, main.viewHeight);
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] handheld screen copy failed: {}", t.toString());
            handheldDue = now + 2.0;
        }
    }

    /** @return true when a handheld frame was captured (consuming this frame's budget) */
    private static boolean tickHandheld(Minecraft mc, LocalPlayer player, double now) {
        boolean holding = holdingHandheld(player);
        if (!holding) {
            if (handheldFeed != null) {
                handheldFeed.release();
                handheldFeed = null;
            }
            return false;
        }
        if (now < handheldDue) {
            return false;
        }
        handheldDue = now + 1.0 / HANDHELD_FPS;
        if (handheldFeed == null) {
            handheldFeed = new Feed(null);
        }
        try {
            completePendingReadback(handheldFeed);
            captureTarget = CAPTURE_TARGETS.computeIfAbsent(
                    ((long) HANDHELD_WIDTH << 32) | HANDHELD_HEIGHT,
                    key -> new MainTarget(HANDHELD_WIDTH, HANDHELD_HEIGHT));
            float yaw = player.getViewYRot(1.0F);
            float pitch = player.getViewXRot(1.0F);
            // Operator wobble: two incommensurate sines per axis so it never loops visibly.
            float wobbleYaw = (float) (Math.sin(now * 1.7) * 0.5 + Math.sin(now * 4.3) * 0.2);
            float wobblePitch = (float) (Math.sin(now * 2.1 + 1.0) * 0.4 + Math.sin(now * 5.7) * 0.15);
            Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
            NdiCameraBlockEntity.ViewState view = new NdiCameraBlockEntity.ViewState(
                    player.getEyePosition(1.0F).add(forward.scale(HANDHELD_FORWARD)),
                    yaw + wobbleYaw, pitch + wobblePitch);
            renderView(mc, view, mc.options.fov().get());
            readAndSend(handheldFeed, "MC Handheld " + player.getGameProfile().getName(),
                    HANDHELD_FPS, false, captureTarget.viewWidth, captureTarget.viewHeight);
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] handheld capture failed: {}", t.toString());
            handheldDue = now + 2.0; // back off, then retry
        }
        return true;
    }

    // --- NDI PTZ control ---------------------------------------------------
    //
    // A PTZ rig's sender advertises the NDI PTZ capability, so receivers (NDI Studio
    // Monitor, hardware controllers, vMix, ...) show pan/tilt/zoom controls for it.
    // Their commands come back over the connection as XML metadata, polled here and
    // applied to the rig — locally for instant response, and via the normal config
    // packet (rate-limited) so the change is server-authoritative and synced.

    /** FOV degrees per second at full zoom-speed deflection. */
    private static final float PTZ_ZOOM_FOV_PER_SEC = 50.0F;

    /** Widest and tightest FOV reachable from a PTZ controller's zoom axis. */
    private static final float PTZ_FOV_WIDE = 110.0F;
    private static final float PTZ_FOV_TELE = 10.0F;

    private static void advertisePtz(DevolaySender sender) {
        try (DevolayMetadataFrame caps = new DevolayMetadataFrame()) {
            caps.setData("<ndi_capabilities ntk_ptz=\"true\"/>");
            sender.addConnectionMetadata(caps);
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] could not advertise PTZ capability: {}", t.toString());
        }
    }

    /** Drains pending PTZ commands from this rig's connected receivers and applies them. */
    private static void pollPtzControl(Feed feed, double now) {
        NdiCameraBlockEntity be = feed.be;
        if (feed.sender == null || be.getKind() != CameraKind.PTZ) {
            return;
        }
        if (feed.ptzMeta == null) {
            feed.ptzMeta = new DevolayMetadataFrame();
        }
        for (int i = 0; i < 16; i++) {
            DevolayFrameType type;
            try {
                type = feed.sender.sendCapture(feed.ptzMeta, 0);
            } catch (IllegalArgumentException unknownType) {
                continue; // NDI 6 frame type unknown to Devolay's enum; skip it
            } catch (Throwable t) {
                return;
            }
            if (type != DevolayFrameType.METADATA) {
                break;
            }
            String xml = feed.ptzMeta.getData();
            if (xml != null) {
                handlePtzCommand(feed, xml);
            }
        }

        // Continuous motion: integrate the controller's velocity axes. The configured
        // slew rate is the max pan/tilt speed, matching how the head eases visually.
        double dt = Math.min(now - feed.ptzLastIntegrate, 0.25);
        feed.ptzLastIntegrate = now;
        if (dt > 0 && (feed.ptzPanSpeed != 0 || feed.ptzTiltSpeed != 0 || feed.ptzZoomSpeed != 0)) {
            applyPtz(feed,
                    be.getPan() + feed.ptzPanSpeed * be.getPtzSpeed() * (float) dt,
                    be.getTilt() + feed.ptzTiltSpeed * be.getPtzSpeed() * (float) dt,
                    be.getFov() - feed.ptzZoomSpeed * PTZ_ZOOM_FOV_PER_SEC * (float) dt);
        }

        // Server sync, rate-limited: the local applyConfig above gives instant response;
        // this persists it and relays to other players.
        if (feed.ptzDirty && now - feed.ptzLastSent > 0.1) {
            feed.ptzLastSent = now;
            feed.ptzDirty = false;
            NetworkHandler.CHANNEL.sendToServer(new UpdateCameraConfigPacket(
                    be.getBlockPos(), be.getSourceName(), be.isActive(), be.getResolutionIndex(),
                    be.getFps(), be.getFov(), be.getPan(), be.getTilt(),
                    be.getPtzSpeed(), 0.0F, 0.0F));
        }
    }

    private static void handlePtzCommand(Feed feed, String xml) {
        NdiCameraBlockEntity be = feed.be;
        // Order matters: "pan_tilt_speed" contains "pan_tilt", "zoom_speed" contains "zoom".
        if (xml.contains("<ntk_ptz_pan_tilt_speed")) {
            feed.ptzPanSpeed = ptzAttr(xml, "pan_speed", 0.0F);
            feed.ptzTiltSpeed = ptzAttr(xml, "tilt_speed", 0.0F);
        } else if (xml.contains("<ntk_ptz_pan_tilt")) {
            feed.ptzPanSpeed = 0.0F;
            feed.ptzTiltSpeed = 0.0F;
            applyPtz(feed, ptzAttr(xml, "pan", 0.0F) * 180.0F,
                    ptzAttr(xml, "tilt", 0.0F) * 85.0F, be.getFov());
        } else if (xml.contains("<ntk_ptz_zoom_speed")) {
            feed.ptzZoomSpeed = ptzAttr(xml, "zoom_speed", 0.0F);
        } else if (xml.contains("<ntk_ptz_zoom")) {
            feed.ptzZoomSpeed = 0.0F;
            float zoom = ptzAttr(xml, "zoom", 0.5F);
            applyPtz(feed, be.getPan(), be.getTilt(),
                    PTZ_FOV_WIDE - zoom * (PTZ_FOV_WIDE - PTZ_FOV_TELE));
        } else if (xml.contains("<ntk_ptz_store_preset")) {
            int idx = (int) ptzAttr(xml, "index", -1.0F);
            if (idx >= 0 && idx < feed.ptzPresets.length) {
                feed.ptzPresets[idx] = new float[]{be.getPan(), be.getTilt(), be.getFov()};
            }
        } else if (xml.contains("<ntk_ptz_recall_preset")) {
            int idx = (int) ptzAttr(xml, "index", -1.0F);
            if (idx >= 0 && idx < feed.ptzPresets.length && feed.ptzPresets[idx] != null) {
                float[] p = feed.ptzPresets[idx];
                feed.ptzPanSpeed = 0.0F;
                feed.ptzTiltSpeed = 0.0F;
                feed.ptzZoomSpeed = 0.0F;
                applyPtz(feed, p[0], p[1], p[2]);
            }
        }
        // Focus / exposure / white-balance commands have no analogue here and are ignored.
    }

    /** Applies new pan/tilt/fov locally (instant easing target) and marks for server sync. */
    private static void applyPtz(Feed feed, float pan, float tilt, float fov) {
        NdiCameraBlockEntity be = feed.be;
        be.applyConfig(be.getSourceName(), be.isActive(), be.getResolutionIndex(), be.getFps(),
                fov, pan, tilt, be.getPtzSpeed(), 0.0F, 0.0F);
        feed.ptzDirty = true;
    }

    /** Pulls a float attribute out of an NDI PTZ metadata tag; no XML library needed. */
    private static float ptzAttr(String xml, String name, float fallback) {
        int at = xml.indexOf(name + "=");
        if (at < 0) {
            return fallback;
        }
        int q1 = xml.indexOf('"', at);
        int q2 = q1 < 0 ? -1 : xml.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return fallback;
        }
        try {
            float value = Float.parseFloat(xml.substring(q1 + 1, q2));
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Brings the feed's NDI sender online, independently of capturing. A rig must be
     * discoverable (and answer connection queries) before anyone can subscribe to it,
     * and we only want to spend a world render on rigs that someone is actually watching
     * — so the sender exists first and captures follow demand.
     */
    private static void ensureSender(Feed feed, String name, boolean ptz, int width, int height, int fps) {
        if (feed.sender != null && name.equals(feed.senderName)) {
            return;
        }
        feed.closeSender();
        // Unclocked: the default clocked sender BLOCKS each send to pace frames to the
        // declared rate, stalling the render thread and delivering bursty, late frames
        // that NDI receivers grade as unreliable. We pace captures ourselves.
        feed.sender = new DevolaySender(name, null, false, false);
        feed.senderName = name;
        feed.viewers = 0;
        feed.viewersCheckedAt = 0.0;
        if (ptz) {
            // Tells NDI receivers this source is a controllable PTZ head, so tools like
            // Studio Monitor show their pan/tilt/zoom controls for it.
            advertisePtz(feed.sender);
        }
        LOGGER.info("[ndidisplays] NDI sender '{}' online ({}x{}@{})", name, width, height, fps);
    }

    /**
     * True when at least one NDI receiver is subscribed to this rig — an in-game LED wall,
     * Studio Monitor, OBS, anything. Rigs nobody is watching are never rendered, which is
     * what makes a stage full of placed rigs affordable. Polled, not per-frame.
     */
    private static boolean hasViewers(Feed feed, double now) {
        if (feed.sender == null) {
            return false;
        }
        if (now - feed.viewersCheckedAt > 0.5) {
            feed.viewersCheckedAt = now;
            try {
                feed.viewers = feed.sender.getConnectionCount(0);
            } catch (Throwable t) {
                feed.viewers = 1; // can't tell: assume watched rather than go dark
            }
        }
        return feed.viewers > 0;
    }

    /** Sends one all-black frame on this feed's existing sender ("camera off air"). */
    private static void sendBlackFrame(Feed feed) {
        try {
            int width = feed.be.getWidth();
            int height = feed.be.getHeight();
            int size = width * height * 4;
            ByteBuffer buf = feed.stagingBuffer(size);
            if (feed.sender == null) {
                return; // stagingBuffer closes the sender when it has to grow
            }
            buf.clear();
            buf.limit(size);
            MemoryUtil.memSet(buf, 0);
            try (DevolayVideoFrame frame = new DevolayVideoFrame()) {
                frame.setResolution(width, height);
                frame.setFourCCType(DevolayFrameFourCCType.BGRX);
                frame.setLineStride(width * 4);
                frame.setFrameRate(feed.be.getFps(), 1);
                frame.setData(buf);
                feed.sender.sendVideoFrameAsync(frame);
            }
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] could not send pause frame: {}", t.toString());
        }
    }

    /**
     * Shader-mode capture: the rig view renders through the shader pack's complete
     * pipeline into the real, window-sized main target — no target swap, no window-size
     * spoofing, no vanilla-renderer field surgery, all of which fight the pack (Iris
     * sizes its G-buffers from the window, so spoofing it forced a full pipeline resize
     * every capture — the old black-screen bug). Runs before the player's frame, which
     * then overwrites the main target, so the capture is never visible on screen.
     * The feed is window-resolution with the pack's full visuals.
     */
    private static void captureAndSendShaderMode(Minecraft mc, Feed feed) {
        NdiCameraBlockEntity be = feed.be;
        completePendingReadback(feed);
        NdiCameraBlockEntity.ViewState view = be.getViewState(1.0F);
        renderViewShaderMode(mc, view, be.getFov());
        RenderTarget main = mc.getMainRenderTarget();
        captureTarget = main;
        // Declare the rate we can actually deliver under the shader-mode throttle:
        // promising the rig's configured fps while sending less reads as an unreliable
        // source to NDI monitors.
        int effectiveFps = Math.min(be.getFps(), (int) SHADER_CAPTURE_MAX_FPS);
        readAndSend(feed, be.getEffectiveSourceName(), effectiveFps,
                be.getKind() == CameraKind.PTZ, main.viewWidth, main.viewHeight);

        // Diagnostic: log what the capture actually contains, so a black-screen report
        // can be split into "capture black" vs "player frame black" from the log alone.
        double now = GLFW.glfwGetTime();
        if (now - lastFeedLumLog > 2.0) {
            lastFeedLumLog = now;
            ByteBuffer buf = feed.sendBuffers[feed.sendIndex];
            long sum = 0;
            int samples = 0;
            int size = main.viewWidth * main.viewHeight * 4;
            for (int i = 0; i + 2 < size; i += 4097 * 4) {
                sum += (buf.get(i) & 0xFF) + (buf.get(i + 1) & 0xFF) + (buf.get(i + 2) & 0xFF);
                samples += 3;
            }
            LOGGER.info("[ndidisplays] shader-mode feed '{}' avg luminance {}/255",
                    feed.senderName, samples == 0 ? 0 : sum / samples);
        }
    }

    /**
     * The lean state swap for shader mode: only the camera itself is redirected. The
     * pack's pipeline and Embeddium's chunk renderer handle an arbitrary camera position
     * per render on their own — that is their normal job — so none of the vanilla
     * LevelRenderer bookkeeping from {@link #renderView} applies here.
     */
    private static void renderViewShaderMode(Minecraft mc, NdiCameraBlockEntity.ViewState view, float fov) {
        LocalPlayer player = mc.player;
        Level level = player.level();
        Camera camera = mc.gameRenderer.getMainCamera();

        Entity oldCameraEntity = mc.cameraEntity;
        float oldEyeHeight = camera.eyeHeight;
        float oldEyeHeightO = camera.eyeHeightOld;
        CameraType oldCameraType = mc.options.getCameraType();

        Marker cameraEntity = new Marker(EntityType.MARKER, level);
        cameraEntity.setPos(view.pos());
        cameraEntity.xo = view.pos().x;
        cameraEntity.yo = view.pos().y;
        cameraEntity.zo = view.pos().z;
        cameraEntity.setYRot(view.yaw());
        cameraEntity.yRotO = view.yaw();
        cameraEntity.setXRot(view.pitch());
        cameraEntity.xRotO = view.pitch();

        mc.renderBuffers().bufferSource().endBatch();
        mc.gameRenderer.setRenderBlockOutline(false);
        mc.gameRenderer.setRenderHand(false);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        camera.eyeHeight = camera.eyeHeightOld = 0.0F;
        mc.cameraEntity = cameraEntity;
        capturing = true;
        captureFov = fov;
        mc.getMainRenderTarget().bindWrite(true);
        try {
            mc.gameRenderer.renderLevel(1.0F, 0L, new PoseStack());
        } finally {
            capturing = false;
            mc.cameraEntity = oldCameraEntity;
            cameraEntity.discard();
            mc.options.setCameraType(oldCameraType);
            mc.gameRenderer.setRenderBlockOutline(true);
            mc.gameRenderer.setRenderHand(true);
            camera.eyeHeight = oldEyeHeight;
            camera.eyeHeightOld = oldEyeHeightO;
            Entity restored = oldCameraEntity == null ? player : oldCameraEntity;
            camera.setup(level, restored, !mc.options.getCameraType().isFirstPerson(),
                    mc.options.getCameraType().isMirrored(), 1.0F);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void captureAndSend(Minecraft mc, Feed feed) {
        NdiCameraBlockEntity be = feed.be;
        // Collect and send the PREVIOUS capture first — its GPU readback finished long
        // ago, so this is a cheap map+copy rather than a pipeline stall.
        completePendingReadback(feed);
        int width = be.getWidth();
        int height = be.getHeight();

        // Must be a real MainTarget: mods that post-process the "main" target
        // (e.g. Shimmer's bloom mixins) cast it to interfaces only MainTarget
        // implements, and they run inside our capture's renderLevel too.
        captureTarget = CAPTURE_TARGETS.computeIfAbsent(((long) width << 32) | height,
                key -> new MainTarget(width, height));

        NdiCameraBlockEntity.ViewState view = be.getViewState(1.0F);
        // Probe the viewport through the render stages of the frames we also dump.
        probeViewport = DEBUG_GEOMETRY
                && (feed.framesCaptured + 1 == 30 || (feed.framesCaptured + 1) % 300 == 0);
        if (DEBUG_GEOMETRY && feed.loggedGeometry != 2) {
            // One line per rig, twice, so a mis-framed feed can be checked against the real
            // world instead of guessed at from the picture: -Dndidisplays.debugCapture=true
            feed.loggedGeometry++;
            LOGGER.info("[ndidisplays] capture '{}' kind={} block={} eye=({}, {}, {}) yaw={} pitch={} fov={} | player=({}, {}, {}) eyeY={}",
                    be.getEffectiveSourceName(), be.getKind(), be.getBlockPos(),
                    String.format("%.3f", view.pos().x), String.format("%.3f", view.pos().y),
                    String.format("%.3f", view.pos().z),
                    String.format("%.1f", view.yaw()), String.format("%.1f", view.pitch()),
                    String.format("%.1f", be.getFov()),
                    String.format("%.3f", mc.player.getX()), String.format("%.3f", mc.player.getY()),
                    String.format("%.3f", mc.player.getZ()),
                    String.format("%.3f", mc.player.getEyeY()));
        }
        renderView(mc, view, be.getFov());
        probeViewport = false;
        // Read back the region that was actually rendered. MainTarget.allocateAttachments
        // walks Dimension.listWithFallback and keeps the first size that allocates, so the
        // target's real dimensions are not guaranteed to be the ones we asked for.
        // Advertise the rate we are actually pacing at, so receivers see a source that keeps
        // its promise rather than one perpetually late for a 30 fps claim.
        readAndSend(feed, be.getEffectiveSourceName(), effectiveFps(be),
                be.getKind() == CameraKind.PTZ,
                captureTarget.viewWidth, captureTarget.viewHeight);
        if (DEBUG_GEOMETRY) {
            maybeDumpCapture(mc, feed, view, captureTarget.viewWidth, captureTarget.viewHeight);
        }
    }

    /**
     * Debug only: writes the exact frame that went to NDI as a PNG, together with one log
     * line holding the camera pose and every nearby entity's true position at that instant.
     * The pair lets a mis-framed feed be checked arithmetically (project entity through the
     * logged pose, compare against its pixel position) instead of guessed at from photos of
     * the wall — which also folds in the wall shader and stream latency.
     */
    private static void maybeDumpCapture(Minecraft mc, Feed feed,
                                         NdiCameraBlockEntity.ViewState view, int width, int height) {
        feed.framesCaptured++;
        if (feed.framesCaptured != 30 && feed.framesCaptured % 300 != 0) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("capture-debug");
            java.nio.file.Files.createDirectories(dir);
            String safe = feed.be.getEffectiveSourceName().replaceAll("[^A-Za-z0-9._-]", "_");
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            ByteBuffer buf = feed.sendBuffers[feed.sendIndex]; // top-down BGRA, exactly as sent
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    img.setRGB(x, y, (buf.get(i + 2) & 0xFF) << 16
                            | (buf.get(i + 1) & 0xFF) << 8
                            | (buf.get(i) & 0xFF));
                }
            }
            java.io.File out = dir.resolve(safe + "-f" + feed.framesCaptured + ".png").toFile();
            javax.imageio.ImageIO.write(img, "png", out);
            StringBuilder entities = new StringBuilder();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.position().distanceTo(view.pos()) < 40.0) {
                    entities.append(String.format("%s(%.2f, %.2f, %.2f) ",
                            e.getName().getString(), e.getX(), e.getY(), e.getZ()));
                }
            }
            LOGGER.info("[ndidisplays] capture dump {} | eye=({}, {}, {}) yaw={} pitch={} fov={} {}x{} | entities: {}",
                    out.getPath(),
                    String.format("%.3f", view.pos().x), String.format("%.3f", view.pos().y),
                    String.format("%.3f", view.pos().z),
                    String.format("%.2f", view.yaw()), String.format("%.2f", view.pitch()),
                    String.format("%.1f", feed.be.getFov()), width, height, entities);
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] capture debug dump failed: {}", t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void renderView(Minecraft mc, NdiCameraBlockEntity.ViewState view, float fov) {
        LocalPlayer player = mc.player;
        Level level = player.level();
        LevelRenderer lr = mc.levelRenderer;
        Camera camera = mc.gameRenderer.getMainCamera();
        Window window = mc.getWindow();

        // --- save state
        Entity oldCameraEntity = mc.cameraEntity;
        int oldWidth = window.getWidth();
        int oldHeight = window.getHeight();
        float oldEyeHeight = camera.eyeHeight;
        float oldEyeHeightO = camera.eyeHeightOld;
        CameraType oldCameraType = mc.options.getCameraType();
        RenderTarget oldMainTarget = mc.mainRenderTarget;
        RenderTarget oldEntityOutline = lr.entityTarget;
        RenderTarget oldTranslucent = lr.translucentTarget;
        RenderTarget oldItemEntity = lr.itemEntityTarget;
        RenderTarget oldWeather = lr.weatherTarget;
        PostChain oldTransparency = lr.transparencyChain;
        int oldLastChunkX = lr.lastCameraChunkX;
        int oldLastChunkY = lr.lastCameraChunkY;
        int oldLastChunkZ = lr.lastCameraChunkZ;
        // The state setupRender consults to decide whether to rebuild the shared chunk
        // graph. All of it has to be restored, or the player's next frame sees the camera's
        // cell in prevCam* and schedules a full rebuild — every frame, forever.
        double oldPrevCamX = lr.prevCamX;
        double oldPrevCamY = lr.prevCamY;
        double oldPrevCamZ = lr.prevCamZ;
        boolean oldNeedsFullUpdate = lr.needsFullRenderChunkUpdate;
        LevelRenderer.RenderChunkStorage oldChunkStorage = lr.renderChunkStorage.get();
        ObjectArrayList<LevelRenderer.RenderChunkInfo> oldVisible =
                (ObjectArrayList<LevelRenderer.RenderChunkInfo>) lr.renderChunksInFrustum.clone();

        Marker cameraEntity = new Marker(EntityType.MARKER, level);
        cameraEntity.setPos(view.pos());
        cameraEntity.xo = view.pos().x;
        cameraEntity.yo = view.pos().y;
        cameraEntity.zo = view.pos().z;
        cameraEntity.setYRot(view.yaw());
        cameraEntity.yRotO = view.yaw();
        cameraEntity.setXRot(view.pitch());
        cameraEntity.xRotO = view.pitch();

        mc.renderBuffers().bufferSource().endBatch();
        mc.gameRenderer.setRenderBlockOutline(false);
        mc.gameRenderer.setRenderHand(false);
        // NOTE: never use panoramic mode here — getFov() early-returns 90 degrees in
        // panoramic mode BEFORE the Forge FOV event fires, which would lock every
        // feed to ultra-wide and disable the zoom override. Bobbing/hurt-tilt are
        // already impossible because the capture camera entity is a Marker.
        window.setWidth(captureTarget.width);
        window.setHeight(captureTarget.height);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        camera.eyeHeight = camera.eyeHeightOld = 0.0F;
        mc.cameraEntity = cameraEntity;
        // The entity-outline target must go too, not just the Fabulous targets: renderLevel
        // clears it before the entity loop whenever outlines are enabled, and clear() runs
        // bindWrite(true) — which sets the GL viewport to that target's size (the real
        // window) — before main is re-bound with bindWrite(false), which does NOT reset the
        // viewport. Vanilla never notices because both targets are window-sized; in a
        // capture they differ, so every entity rendered after that point was drawn through
        // a window-sized viewport into the capture target: entities (and everything after
        // them) landed displaced and scaled by the window/capture size ratio.
        lr.entityTarget = null;
        lr.translucentTarget = null; // Fabulous targets break off-screen capture
        lr.itemEntityTarget = null;
        lr.weatherTarget = null;
        lr.transparencyChain = null;
        // Do NOT touch lastCameraChunk* here. setupRender compares those against
        // minecraft.player's position, not the camera's, so writing the camera's section
        // into them does not stop re-centring — it guarantees a mismatch and forces a
        // viewArea.repositionCamera() sweep over the entire chunk grid on every capture.
        // Left alone they already match the player, so the sweep is skipped.
        //
        // Instead pin the fields that actually gate a rebuild: claim the camera's own
        // cell as "already seen" and clear the pending flag, so our capture never
        // schedules a full render-chunk BFS or swaps the shared RenderChunkStorage out
        // from under the player. The cost is that a camera only sees sections the
        // player's graph already contains, which is the documented trade-off.
        lr.prevCamX = Math.floor(view.pos().x / 8.0);
        lr.prevCamY = Math.floor(view.pos().y / 8.0);
        lr.prevCamZ = Math.floor(view.pos().z / 8.0);
        lr.needsFullRenderChunkUpdate = false;
        lr.needsFrustumUpdate.set(true); // rebuild visible set for the camera frustum

        capturing = true;
        captureFov = fov;
        captureTarget.clear(Minecraft.ON_OSX);
        captureTarget.bindWrite(true);
        mc.mainRenderTarget = captureTarget;
        // Shimmer's post passes composite against the real screen framebuffer;
        // inside a capture they would paste the player's HUD frame into the feed.
        boolean[] shimmerSaved = dev.nano.ndidisplays.client.render.LedWallRenderer.SHIMMER_LOADED
                ? dev.nano.ndidisplays.client.render.ShimmerCompat.suppressPostChains()
                : null;
        // Embeddium's equivalent of the chunk-graph pin above: without it, every capture
        // triggers two full synchronous occlusion re-culls (rig view, then player again).
        dev.nano.ndidisplays.client.render.EmbeddiumCompat.pinCamera(view.pos(), view.pitch(), view.yaw());

        try {
            mc.gameRenderer.renderLevel(1.0F, 0L, new PoseStack());
            if (DEBUG_GEOMETRY && probeViewport) {
                // The Camera object still holds whatever pose the capture actually rendered
                // with — comparing it against the requested view catches any drift injected
                // by camera.setup / event handlers between our numbers and the real pass.
                LOGGER.info("[ndidisplays] capture actual camera pos=({}, {}, {}) yaw={} pitch={} | requested eye=({}, {}, {}) yaw={} pitch={}",
                        String.format("%.3f", camera.getPosition().x),
                        String.format("%.3f", camera.getPosition().y),
                        String.format("%.3f", camera.getPosition().z),
                        String.format("%.2f", camera.getYRot()), String.format("%.2f", camera.getXRot()),
                        String.format("%.3f", view.pos().x), String.format("%.3f", view.pos().y),
                        String.format("%.3f", view.pos().z),
                        String.format("%.2f", view.yaw()), String.format("%.2f", view.pitch()));
            }
        } finally {
            dev.nano.ndidisplays.client.render.EmbeddiumCompat.restoreCamera();
            if (shimmerSaved != null) {
                dev.nano.ndidisplays.client.render.ShimmerCompat.restorePostChains(shimmerSaved);
            }
            capturing = false;
            captureTarget.unbindWrite();

            // --- restore state
            mc.mainRenderTarget = oldMainTarget;
            mc.cameraEntity = oldCameraEntity;
            cameraEntity.discard();
            window.setWidth(oldWidth);
            window.setHeight(oldHeight);
            mc.options.setCameraType(oldCameraType);
            mc.gameRenderer.setRenderBlockOutline(true);
            mc.gameRenderer.setRenderHand(true);
            camera.eyeHeight = oldEyeHeight;
            camera.eyeHeightOld = oldEyeHeightO;
            lr.entityTarget = oldEntityOutline;
            lr.translucentTarget = oldTranslucent;
            lr.itemEntityTarget = oldItemEntity;
            lr.weatherTarget = oldWeather;
            lr.transparencyChain = oldTransparency;
            lr.lastCameraChunkX = oldLastChunkX;
            lr.lastCameraChunkY = oldLastChunkY;
            lr.lastCameraChunkZ = oldLastChunkZ;
            lr.prevCamX = oldPrevCamX;
            lr.prevCamY = oldPrevCamY;
            lr.prevCamZ = oldPrevCamZ;
            lr.needsFullRenderChunkUpdate = oldNeedsFullUpdate;
            lr.renderChunkStorage.set(oldChunkStorage);
            lr.renderChunksInFrustum.clear();
            lr.renderChunksInFrustum.addAll(oldVisible);
            lr.needsFrustumUpdate.set(true); // next main frame re-applies the player frustum
            Entity restored = oldCameraEntity == null ? player : oldCameraEntity;
            camera.setup(level, restored, !mc.options.getCameraType().isFirstPerson(),
                    mc.options.getCameraType().isMirrored(), 1.0F);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /**
     * Two-phase capture readback. Phase 1 (here): queue an asynchronous copy of the
     * capture target into the feed's pixel-pack buffer — returns without waiting for
     * the GPU. Phase 2 ({@link #completePendingReadback}) runs at the feed's next
     * capture, when the GPU has long finished, and does the map + send. The old
     * synchronous glReadPixels stalled the render thread until the whole capture
     * render completed, on every captured frame — the main source of camera lag.
     */
    private static void readAndSend(Feed feed, String name, int fps, boolean ptz, int width, int height) {
        int size = width * height * 4;
        if (feed.pbo == 0) {
            feed.pbo = GL15C.glGenBuffers();
            feed.pboCapacity = 0;
        }
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, feed.pbo);
        if (feed.pboCapacity < size) {
            GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, size, GL15C.GL_STREAM_READ);
            feed.pboCapacity = size;
        }

        // glReadPixels, not glGetTexImage: it writes exactly the requested rectangle,
        // where glGetTexImage writes the whole (possibly larger) texture level. Every
        // pack parameter that affects the write is set explicitly — a stale non-zero
        // PACK_ROW_LENGTH or PACK_SKIP_ROWS inherited from other code would overrun.
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, captureTarget.frameBufferId);
        GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
        GL11C.glReadPixels(0, 0, width, height, GL12C.GL_BGRA, GL11C.GL_UNSIGNED_BYTE, 0L);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, 0);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);

        feed.pboPending = true;
        feed.pboWidth = width;
        feed.pboHeight = height;
        feed.pboFps = fps;
        feed.pboPtz = ptz;
        feed.pboName = name;
    }

    /** Phase 2: collects the previously queued readback and sends it to NDI. */
    private static void completePendingReadback(Feed feed) {
        if (!feed.pboPending || feed.pbo == 0) {
            return;
        }
        feed.pboPending = false;
        int width = feed.pboWidth;
        int height = feed.pboHeight;
        String name = feed.pboName;
        int fps = feed.pboFps;
        boolean ptz = feed.pboPtz;
        int size = width * height * 4;

        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, feed.pbo);
        ByteBuffer mapped = GL15C.glMapBuffer(GL21C.GL_PIXEL_PACK_BUFFER, GL15C.GL_READ_ONLY);
        if (mapped == null || mapped.capacity() < size) {
            GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            return;
        }
        ByteBuffer sendBuffer = feed.stagingBuffer(size);

        // GL rows are bottom-up; flip to top-down for NDI while copying out of the map.
        int stride = width * 4;
        sendBuffer.clear();
        for (int y = 0; y < height; y++) {
            int srcRow = (height - 1 - y) * stride;
            mapped.limit(srcRow + stride).position(srcRow);
            sendBuffer.put(mapped);
        }
        sendBuffer.flip();
        GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);

        ensureSender(feed, name, ptz, width, height, fps);

        try (DevolayVideoFrame frame = new DevolayVideoFrame()) {
            frame.setResolution(width, height);
            // BGRX, not BGRA: the pixels come straight out of Minecraft's framebuffer,
            // whose alpha channel is meaningless leftovers from blended sky/translucency.
            // The screen never reads it, but NDI receivers honour alpha and rendered the
            // feed with transparent holes. BGRX is the same layout with alpha ignored.
            frame.setFourCCType(DevolayFrameFourCCType.BGRX);
            frame.setLineStride(stride);
            frame.setFrameRate(fps, 1);
            frame.setData(sendBuffer);
            // Async: returns immediately; NDI reads the buffer until the next async
            // submit on this sender, which by then targets the other buffer of the pair.
            feed.sender.sendVideoFrameAsync(frame);
        }
    }
}
