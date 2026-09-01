package dev.nano.ndidisplays.client.computer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ComputerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every running computer on this client: its {@link ComputerOS} and the private framebuffer
 * its desktop renders into.
 *
 * One offscreen GUI pass per computer per frame-interval paints the desktop into that target;
 * the in-world monitor, the sit-down screen, and the NDI publisher all just read the resulting
 * texture. The pass runs at RenderTick END — after the player's frame, so it can never disturb
 * it — and each machine renders at its own configured FPS, so a wall of computers costs what
 * their combined refresh rates cost, not a fixed per-frame tax.
 *
 * The desktop is laid out in logical points (height {@link ComputerOS#LOGICAL_H}) and scaled to
 * the output resolution: at 1080p the OS draws 3x, so the same UI is legible on a 480p feed and
 * crisp on a 1080p one.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, value = Dist.CLIENT)
public final class Computers {

    /** A machine goes to sleep when nothing has drawn or used it for this long. */
    private static final long IDLE_MS = 10_000L;

    public static final class Entry {
        public TextureTarget target;
        public ComputerOS os;
        ComputerBlockEntity be;
        long lastSeen;
        long nextFrame;
    }

    private static final Map<BlockPos, Entry> COMPUTERS = new ConcurrentHashMap<>();

    private Computers() {
    }

    /** Renderer / screen check-in: this computer is visible or in use and must keep running. */
    public static Entry note(ComputerBlockEntity be) {
        Entry e = COMPUTERS.computeIfAbsent(be.getBlockPos().immutable(), p -> new Entry());
        e.lastSeen = System.currentTimeMillis();
        e.be = be;
        return e;
    }

    /** The desktop texture for a computer, or 0 when it has not rendered yet. */
    public static int textureId(BlockPos pos) {
        Entry e = COMPUTERS.get(pos.immutable());
        return e == null || e.target == null ? 0 : e.target.getColorTextureId();
    }

    /** The live OS for input routing, created on demand by the render pass. */
    public static ComputerOS os(BlockPos pos) {
        Entry e = COMPUTERS.get(pos.immutable());
        return e == null ? null : e.os;
    }

    public static void close(BlockPos pos) {
        Entry e = COMPUTERS.remove(pos.immutable());
        if (e != null) {
            shutdownEntry(e);
        }
    }

    public static void closeAll() {
        for (Iterator<Map.Entry<BlockPos, Entry>> it = COMPUTERS.entrySet().iterator();
                it.hasNext(); ) {
            shutdownEntry(it.next().getValue());
            it.remove();
        }
    }

    private static void shutdownEntry(Entry e) {
        if (e.os != null) {
            e.os.shutdown();
        }
        if (e.target != null) {
            e.target.destroyBuffers();
        }
    }

    // ------------------------------------------------------------------ the desktop pass

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || dev.nano.ndidisplays.client.CameraFeedManager.isCapturing()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Entry>> it = COMPUTERS.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now - e.lastSeen > IDLE_MS || e.be == null || e.be.isRemoved()) {
                shutdownEntry(e);
                it.remove();
                continue;
            }
            if (now < e.nextFrame) {
                continue;
            }
            e.nextFrame = now + 1000L / Math.max(1, e.be.getFps());
            renderDesktop(mc, e);
        }
    }

    private static void renderDesktop(Minecraft mc, Entry e) {
        int w = e.be.getWidth();
        int h = e.be.getHeight();
        if (e.target == null || e.target.width != w || e.target.height != h) {
            if (e.target != null) {
                e.target.destroyBuffers();
            }
            e.target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        }
        String name = e.be.getEffectiveSourceName();
        if (e.os == null || !e.os.name.equals(name)) {
            if (e.os != null) {
                e.os.shutdown();
            }
            e.os = new ComputerOS(name, e.be.getBlockPos());
        }

        // Logical layout size: the OS thinks in points, the GPU scales them to the output.
        int scale = Math.max(1, h / ComputerOS.LOGICAL_H);
        int lw = w / scale;
        int lh = h / scale;
        e.os.pixelScale = scale;

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        PoseStack modelView = RenderSystem.getModelViewStack();

        e.target.clear(Minecraft.ON_OSX);
        e.target.bindWrite(true);
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0F, lw, lh, 0.0F, -1000.0F, 3000.0F),
                VertexSorting.ORTHOGRAPHIC_Z);
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            e.os.render(g, lw, lh);
            g.flush();
        } finally {
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            e.target.unbindWrite();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** Logical width/height for input mapping, matching the render pass exactly. */
    public static int logicalW(ComputerBlockEntity be) {
        int scale = Math.max(1, be.getHeight() / ComputerOS.LOGICAL_H);
        return be.getWidth() / scale;
    }

    public static int logicalH(ComputerBlockEntity be) {
        int scale = Math.max(1, be.getHeight() / ComputerOS.LOGICAL_H);
        return be.getHeight() / scale;
    }
}
