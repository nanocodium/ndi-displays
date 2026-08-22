package dev.nano.ndidisplays.client.ndi;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.render.ExternalGlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import me.walkerknapp.devolay.DevolayFrameType;
import me.walkerknapp.devolay.DevolayReceiver;
import me.walkerknapp.devolay.DevolayVideoFrame;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * One NDI receiver. A daemon thread captures BGRA frames into a CPU-side back
 * buffer; the render thread uploads the latest frame into a GL texture (with
 * mipmaps, so the shader can pick the correct scaling LOD per LED) on demand.
 */
public class NdiStream {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Give up on a connected source that has sent no video for this long and re-resolve it. */
    private static final long SOURCE_TIMEOUT_MS = 10_000;
    /** Backoff after a receiver fault, so a broken source cannot spin the thread. */
    private static final long RECONNECT_DELAY_MS = 2_000;
    /** Sanity bound per axis — well past 8K, but far short of a corrupt value. */
    private static final int MAX_DIMENSION = 16_384;
    /** Sanity bound on one frame's byte count (1 GiB). */
    private static final long MAX_FRAME_BYTES = 1L << 30;

    private final String requestedName;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile long lastUsed = System.currentTimeMillis();
    private boolean unknownFrameTypeLogged;
    private boolean badGeometryLogged;

    /** Deliberate-leak visibility: how many receivers this session has abandoned (see run()). */
    private static final java.util.concurrent.atomic.AtomicInteger ABANDONED_RECEIVERS =
            new java.util.concurrent.atomic.AtomicInteger();

    private final Object frameLock = new Object();
    private ByteBuffer frameData;
    private int frameWidth;
    private int frameHeight;
    private int frameStride;
    private boolean frameDirty;

    private int glId;
    private int allocatedWidth = -1;
    private int allocatedHeight = -1;
    private ResourceLocation textureLocation;
    private static int nextTextureIndex = 0;

    NdiStream(String requestedName) {
        this.requestedName = requestedName;
        this.thread = new Thread(this::run, "NDI-Receive-" + requestedName);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    void touch() {
        lastUsed = System.currentTimeMillis();
    }

    long getLastUsed() {
        return lastUsed;
    }

    public boolean hasVideo() {
        synchronized (frameLock) {
            return frameData != null || allocatedWidth > 0;
        }
    }

    /**
     * Owns exactly one receiver and one frame for the stream's whole life. When the source
     * disappears or the connection faults, the receiver is *retargeted* with connect(),
     * never destroyed: NDIlib_recv_destroy is implicated in every JVM crash seen with the
     * NDI 6 runtime, so it runs once per stream, on shutdown. A fault must never end this
     * thread either — the renderer keeps the stream alive by touching it every frame, so a
     * dead thread would leave the wall permanently black until a game restart.
     */
    private void run() {
        DevolayReceiver receiver = null;
        DevolayVideoFrame videoFrame = null;
        try {
            receiver = new DevolayReceiver(DevolayReceiver.ColorFormat.BGRX_BGRA,
                    DevolayReceiver.RECEIVE_BANDWIDTH_HIGHEST, true, null);
            videoFrame = new DevolayVideoFrame();
            while (running) {
                try {
                    if (!NdiManager.connectReceiver(receiver, requestedName)) {
                        Thread.sleep(1000);
                        continue;
                    }
                    receive(receiver, videoFrame);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    LOGGER.warn("[ndidisplays] NDI stream '{}' faulted, reconnecting: {}",
                            requestedName, t.toString());
                    sleepOrStop(RECONNECT_DELAY_MS);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] NDI stream '{}' could not start: {}", requestedName, t.toString());
        } finally {
            // The frame must be released first — a captured frame's buffer is owned by the
            // receiver and DevolayVideoFrame.close() frees it *through* that receiver.
            closeQuietly(videoFrame);
            int abandoned = ABANDONED_RECEIVERS.incrementAndGet();
            if (abandoned % 8 == 0) {
                LOGGER.info("[ndidisplays] {} NDI receivers abandoned this session (deliberate:"
                        + " destroying one crashes the NDI 6 runtime); each keeps a few MB and"
                        + " worker threads until the game closes", abandoned);
            }
            // The receiver is deliberately ABANDONED, never destroyed. Every JVM crash
            // observed with the NDI 6 runtime has been inside NDIlib_recv_destroy — either
            // an immediate access violation or silent heap corruption detonating at a later
            // free — including on the very first, perfectly ordered destroy of an idle
            // receiver. Until Devolay ships natives built against NDI 5/6, leaking a
            // receiver (streams are evicted only after 15s unused, so a handful per
            // session) is strictly better than a process crash.
        }
    }

    /** Pumps frames from one connected receiver until the source goes quiet or we stop. */
    private void receive(DevolayReceiver receiver, DevolayVideoFrame videoFrame) {
        long lastVideo = System.currentTimeMillis();
        while (running) {
            DevolayFrameType type;
            try {
                type = receiver.receiveCapture(videoFrame, null, null, 500);
            } catch (IllegalArgumentException unknownType) {
                // Devolay 2.1.0 predates the newer NDI runtimes: NDI 6 emits control
                // frame type 101, which its enum cannot map, so receiveCapture throws.
                // These are never video frames, so treat them as an idle poll. Letting
                // the exception escape used to kill this thread and then crash the JVM
                // while tearing down a receiver with the capture still outstanding.
                if (!unknownFrameTypeLogged) {
                    unknownFrameTypeLogged = true;
                    LOGGER.info("[ndidisplays] NDI source '{}' sends a frame type this NDI binding"
                            + " does not know ({}); ignoring those frames",
                            requestedName, unknownType.getMessage());
                }
                type = null;
            }
            if (type == DevolayFrameType.VIDEO) {
                copyFrame(videoFrame);
                lastVideo = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastVideo > SOURCE_TIMEOUT_MS) {
                return; // source likely gone; re-resolve by name
            }
        }
    }

    private void sleepOrStop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            running = false;
        }
    }

    /** Once per stream: a broken source would otherwise log at the frame rate. */
    private void logBadGeometryOnce(int width, int height, int stride) {
        if (badGeometryLogged) {
            return;
        }
        badGeometryLogged = true;
        LOGGER.warn("[ndidisplays] NDI source '{}' delivered a frame with inconsistent geometry"
                + " ({}x{}, stride {}); dropping frames like it", requestedName, width, height, stride);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] releasing an NDI resource failed: {}", t.toString());
        }
    }

    /**
     * Copies a captured frame into the CPU-side back buffer.
     *
     * The geometry is validated rather than trusted. {@link #uploadIfNeeded()} hands the
     * buffer to {@code glTexSubImage2D} with {@code GL_UNPACK_ROW_LENGTH = stride / 4},
     * and OpenGL cannot bounds-check a raw pointer — so a frame whose reported dimensions
     * do not match the bytes actually delivered would make the driver read past the
     * allocation and take the process down. Anything inconsistent is dropped instead.
     */
    private void copyFrame(DevolayVideoFrame frame) {
        ByteBuffer src = frame.getData();
        if (src == null) {
            return;
        }
        int width = frame.getXResolution();
        int height = frame.getYResolution();
        int stride = frame.getLineStride();
        // Long math: a corrupt stride/height would overflow int and yield a negative size.
        long required = (long) stride * height;
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || stride < (long) width * 4 || required > MAX_FRAME_BYTES) {
            logBadGeometryOnce(width, height, stride);
            return;
        }
        src.rewind();
        if (src.remaining() < required) {
            // A short buffer means the upload would read beyond it; skip this frame.
            logBadGeometryOnce(width, height, stride);
            return;
        }
        int size = (int) required;
        synchronized (frameLock) {
            if (!running) {
                // shutdown() has already freed frameData under this lock; allocating a
                // replacement here would leak it, since nothing will free it again.
                return;
            }
            if (frameData == null || frameData.capacity() < size) {
                if (frameData != null) {
                    MemoryUtil.memFree(frameData);
                }
                frameData = MemoryUtil.memAlloc(size);
            }
            frameData.clear();
            int oldLimit = src.limit();
            src.limit(src.position() + size);
            frameData.put(src);
            src.limit(oldLimit);
            frameData.flip();
            frameWidth = width;
            frameHeight = height;
            frameStride = stride;
            frameDirty = true;
        }
    }

    /** Render thread only: pushes the newest captured frame into the GL texture. */
    public void uploadIfNeeded() {
        RenderSystem.assertOnRenderThread();
        synchronized (frameLock) {
            if (!frameDirty || frameData == null) {
                return;
            }
            if (glId == 0) {
                glId = GlStateManager._genTexture();
                textureLocation = new ResourceLocation(NdiDisplays.MODID, "ndi_stream_" + (nextTextureIndex++));
                Minecraft.getInstance().getTextureManager().register(textureLocation, new ExternalGlTexture(glId));
            }
            int rowLength = frameStride / 4;
            // Every pixel-store parameter the upload depends on is set explicitly. GL
            // pixel-store state is global and vanilla leaves it dirty: NativeImage.upload()
            // sets SKIP_PIXELS/SKIP_ROWS when it pushes an atlas sub-region and never
            // restores them, so inheriting a non-zero SKIP_ROWS here would make the driver
            // start reading whole rows past the end of frameData and kill the process.
            GlStateManager._bindTexture(glId);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);

            // Belt and braces: OpenGL cannot bounds-check the pointer we hand it, so
            // verify the exact byte span the above state implies is really inside the
            // buffer. Dropping a frame is always better than a native crash.
            long needed = (long) (frameHeight - 1) * rowLength * 4L + (long) frameWidth * 4L;
            if (needed > frameData.remaining()) {
                logBadGeometryOnce(frameWidth, frameHeight, frameStride);
                frameDirty = false;
                GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
                return;
            }
            if (allocatedWidth != frameWidth || allocatedHeight != frameHeight) {
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, frameWidth, frameHeight,
                        0, GL12C.GL_BGRA, GL11C.GL_UNSIGNED_BYTE, frameData);
                allocatedWidth = frameWidth;
                allocatedHeight = frameHeight;
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            } else {
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
                        GL12C.GL_BGRA, GL11C.GL_UNSIGNED_BYTE, frameData);
            }
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            frameDirty = false;
        }
    }

    /** Taps per axis per rectangle in {@link #sampleRects}; 36 samples is plenty for a mean. */
    private static final int TAPS_PER_AXIS = 6;

    /**
     * Mean colour of a set of rectangles of the newest frame, as 0..1 components.
     *
     * For content-driven lighting: a screen needs to know roughly what colour it is throwing,
     * per region of its surface. This reads the CPU-side frame the receiver already holds rather
     * than reading back from the GL texture, because a {@code glGetTexImage} of even a 1x1 mip
     * forces a pipeline sync, and this runs for every visible screen every few frames. Sparse by
     * design — a fixed number of taps per rectangle whatever the frame size, so a 4K source costs
     * the same as a 720p one.
     *
     * Averaged in linear light (gamma 2.0 approximation) rather than on the stored values, since
     * averaging gamma-encoded numbers biases towards the darks and would leave every light dimmer
     * than its picture looks.
     *
     * @param rects {@code count} rectangles, each u0,v0,u1,v1 in 0..1 texture space
     * @param count how many rectangles to read from {@code rects}
     * @param out   receives {@code count * 3} components; untouched when this returns false
     * @return false until a frame has arrived
     */
    public boolean sampleRects(float[] rects, int count, float[] out) {
        synchronized (frameLock) {
            if (frameData == null || frameWidth <= 0 || frameHeight <= 0) {
                return false;
            }
            int limit = frameData.limit();
            for (int i = 0; i < count; i++) {
                float u0 = rects[i * 4];
                float v0 = rects[i * 4 + 1];
                float u1 = rects[i * 4 + 2];
                float v1 = rects[i * 4 + 3];
                double sumR = 0.0;
                double sumG = 0.0;
                double sumB = 0.0;
                int taps = 0;
                for (int sy = 0; sy < TAPS_PER_AXIS; sy++) {
                    float fv = v0 + (v1 - v0) * ((sy + 0.5F) / TAPS_PER_AXIS);
                    int py = Math.max(0, Math.min((int) (fv * frameHeight), frameHeight - 1));
                    for (int sx = 0; sx < TAPS_PER_AXIS; sx++) {
                        float fu = u0 + (u1 - u0) * ((sx + 0.5F) / TAPS_PER_AXIS);
                        int px = Math.max(0, Math.min((int) (fu * frameWidth), frameWidth - 1));
                        long off = (long) py * frameStride + (long) px * 4L;
                        if (off < 0L || off + 2L >= limit) {
                            continue;
                        }
                        int base = (int) off;
                        int b = frameData.get(base) & 0xFF;
                        int g = frameData.get(base + 1) & 0xFF;
                        int r = frameData.get(base + 2) & 0xFF;
                        sumR += (double) r * r;
                        sumG += (double) g * g;
                        sumB += (double) b * b;
                        taps++;
                    }
                }
                int o = i * 3;
                if (taps == 0) {
                    out[o] = 0.0F;
                    out[o + 1] = 0.0F;
                    out[o + 2] = 0.0F;
                } else {
                    out[o] = (float) Math.sqrt(sumR / taps) / 255.0F;
                    out[o + 1] = (float) Math.sqrt(sumG / taps) / 255.0F;
                    out[o + 2] = (float) Math.sqrt(sumB / taps) / 255.0F;
                }
            }
            return true;
        }
    }

    /** 0 until the first frame has been uploaded. */
    public int getTextureId() {
        return allocatedWidth > 0 ? glId : 0;
    }

    /** Null until the first frame has been uploaded. */
    public ResourceLocation getTextureLocation() {
        return allocatedWidth > 0 ? textureLocation : null;
    }

    /** Source frame width in pixels, or 0 until the first frame has been uploaded. */
    public int getVideoWidth() {
        return Math.max(0, allocatedWidth);
    }

    /** Source frame height in pixels, or 0 until the first frame has been uploaded. */
    public int getVideoHeight() {
        return Math.max(0, allocatedHeight);
    }

    void shutdown() {
        running = false;
        thread.interrupt();
        int id = glId;
        ResourceLocation loc = textureLocation;
        glId = 0;
        textureLocation = null;
        allocatedWidth = -1;
        allocatedHeight = -1;
        if (id != 0) {
            RenderSystem.recordRenderCall(() -> {
                if (loc != null) {
                    // TextureManager owns the id once registered; release deletes it
                    Minecraft.getInstance().getTextureManager().release(loc);
                } else {
                    GlStateManager._deleteTexture(id);
                }
            });
        }
        // running is already false, so copyFrame() will not allocate a replacement
        // after this frees the buffer.
        synchronized (frameLock) {
            if (frameData != null) {
                MemoryUtil.memFree(frameData);
                frameData = null;
            }
            frameDirty = false;
        }
    }
}
