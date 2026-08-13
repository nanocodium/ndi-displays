package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.CropWindow;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateScreenCropPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The "video processor" screen: shows the live NDI input and lets the user drag a
 * window over it — exactly like mapping an input region to an output on a real LED
 * processor (NovaStar-style). The selected region of the stream is what the screen
 * displays; Apply sends it to the server for the wall / round / curved screen at
 * {@code pos}.
 *
 * Interactions: drag inside the window to move it, drag a corner or an edge to
 * resize, drag on empty preview to draw a fresh window. Full Frame resets.
 */
public class VideoProcessorScreen extends Screen {

    /** Hit radius for corner/edge handles, gui px. */
    private static final int HANDLE = 5;

    private static final int COLOR_FRAME = 0xFF3AD07A;
    private static final int COLOR_HANDLE = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xA8000000;

    private final Screen parent;
    private final BlockPos pos;
    private final String sourceName;

    // Current window, source uv space (v0 = top of the frame).
    private float u0;
    private float v0;
    private float u1;
    private float v1;

    // Preview rectangle, gui px (recomputed in init()).
    private int pvX;
    private int pvY;
    private int pvW;
    private int pvH;

    /**
     * Active drag: 0 none, 1 move, 2 new window; corners 3 TL, 4 TR, 5 BL, 6 BR;
     * edges 7 left, 8 right, 9 top, 10 bottom.
     */
    private int drag;
    private double grabDu;
    private double grabDv;
    private double newAnchorU;
    private double newAnchorV;

    public VideoProcessorScreen(Screen parent, BlockPos pos, String sourceName, CropWindow current) {
        super(Component.translatable("gui.ndidisplays.processor.title"));
        this.parent = parent;
        this.pos = pos;
        this.sourceName = sourceName;
        this.u0 = current.u0();
        this.v0 = current.v0();
        this.u1 = current.u1();
        this.v1 = current.v1();
    }

    @Override
    protected void init() {
        pvW = Math.min(width - 60, 384);
        pvH = pvW * 9 / 16;
        int maxH = height - 110;
        if (pvH > maxH) {
            pvH = Math.max(90, maxH);
            pvW = pvH * 16 / 9;
        }
        pvX = (width - pvW) / 2;
        pvY = 34;

        int y = pvY + pvH + 26;
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.processor.full"), b -> {
                    u0 = 0.0F;
                    v0 = 0.0F;
                    u1 = 1.0F;
                    v1 = 1.0F;
                })
                .bounds(cx - 132, y, 84, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.processor.apply"), b -> apply())
                .bounds(cx - 44, y, 84, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 44, y, 84, 20).build());
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateScreenCropPacket(pos, u0, v0, u1, v1));
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        // Preview backdrop + live video.
        graphics.fill(pvX - 1, pvY - 1, pvX + pvW + 1, pvY + pvH + 1, 0xFF3C3C3C);
        graphics.fill(pvX, pvY, pvX + pvW, pvY + pvH, 0xFF000000);
        NdiStream stream = sourceName.isEmpty() ? null : NdiManager.acquire(sourceName);
        ResourceLocation tex = null;
        int vidW = 0;
        int vidH = 0;
        if (stream != null) {
            stream.uploadIfNeeded();
            tex = stream.getTextureLocation();
            vidW = stream.getVideoWidth();
            vidH = stream.getVideoHeight();
        }
        if (tex != null) {
            graphics.blit(tex, pvX, pvY, pvW, pvH, 0.0F, 0.0F, vidW, vidH, vidW, vidH);
        } else {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.ndidisplays.processor.nosignal"),
                    pvX + pvW / 2, pvY + pvH / 2 - 4, 0x808080);
        }

        // Window rectangle in gui px.
        int wx0 = pvX + Math.round(u0 * pvW);
        int wy0 = pvY + Math.round(v0 * pvH);
        int wx1 = pvX + Math.round(u1 * pvW);
        int wy1 = pvY + Math.round(v1 * pvH);

        // Dim everything outside the window.
        graphics.fill(pvX, pvY, pvX + pvW, wy0, COLOR_DIM);
        graphics.fill(pvX, wy1, pvX + pvW, pvY + pvH, COLOR_DIM);
        graphics.fill(pvX, wy0, wx0, wy1, COLOR_DIM);
        graphics.fill(wx1, wy0, pvX + pvW, wy1, COLOR_DIM);

        // Window frame + rule-of-thirds guides.
        hLine(graphics, wx0, wx1, wy0, COLOR_FRAME);
        hLine(graphics, wx0, wx1, wy1 - 1, COLOR_FRAME);
        vLine(graphics, wx0, wy0, wy1, COLOR_FRAME);
        vLine(graphics, wx1 - 1, wy0, wy1, COLOR_FRAME);
        int guide = 0x403AD07A;
        vLine(graphics, wx0 + (wx1 - wx0) / 3, wy0, wy1, guide);
        vLine(graphics, wx0 + (wx1 - wx0) * 2 / 3, wy0, wy1, guide);
        hLine(graphics, wx0, wx1, wy0 + (wy1 - wy0) / 3, guide);
        hLine(graphics, wx0, wx1, wy0 + (wy1 - wy0) * 2 / 3, guide);

        // Handles: corners + edge midpoints.
        handle(graphics, wx0, wy0);
        handle(graphics, wx1, wy0);
        handle(graphics, wx0, wy1);
        handle(graphics, wx1, wy1);
        handle(graphics, (wx0 + wx1) / 2, wy0);
        handle(graphics, (wx0 + wx1) / 2, wy1);
        handle(graphics, wx0, (wy0 + wy1) / 2);
        handle(graphics, wx1, (wy0 + wy1) / 2);

        // Readout: window as % of the source, and in real pixels when a frame exists.
        String pct = String.format("X %d%%  Y %d%%  W %d%%  H %d%%",
                Math.round(u0 * 100), Math.round(v0 * 100),
                Math.round((u1 - u0) * 100), Math.round((v1 - v0) * 100));
        if (vidW > 0 && vidH > 0) {
            pct += String.format("   |   %d,%d  %d\u00D7%d px",
                    Math.round(u0 * vidW), Math.round(v0 * vidH),
                    Math.round((u1 - u0) * vidW), Math.round((v1 - v0) * vidH));
        }
        graphics.drawCenteredString(font, pct, width / 2, pvY + pvH + 8, 0xC0C0C0);

        String src = sourceName.isEmpty()
                ? Component.translatable("gui.ndidisplays.processor.nosource").getString()
                : sourceName;
        graphics.drawCenteredString(font, src, width / 2, 24, 0x808080);
    }

    private static void handle(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 2, y - 2, x + 2, y + 2, COLOR_HANDLE);
    }

    private static void hLine(GuiGraphics graphics, int x0, int x1, int y, int color) {
        graphics.fill(Math.min(x0, x1), y, Math.max(x0, x1), y + 1, color);
    }

    private static void vLine(GuiGraphics graphics, int x, int y0, int y1, int color) {
        graphics.fill(x, Math.min(y0, y1), x + 1, Math.max(y0, y1), color);
    }

    // ------------------------------------------------------------------ interaction

    private double toU(double mouseX) {
        return (mouseX - pvX) / pvW;
    }

    private double toV(double mouseY) {
        return (mouseY - pvY) / pvH;
    }

    private boolean near(double mouse, double target) {
        return Math.abs(mouse - target) <= HANDLE;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= pvX - HANDLE && mouseX <= pvX + pvW + HANDLE
                && mouseY >= pvY - HANDLE && mouseY <= pvY + pvH + HANDLE) {
            double wx0 = pvX + u0 * pvW;
            double wy0 = pvY + v0 * pvH;
            double wx1 = pvX + u1 * pvW;
            double wy1 = pvY + v1 * pvH;
            boolean insideX = mouseX > wx0 + HANDLE && mouseX < wx1 - HANDLE;
            boolean insideY = mouseY > wy0 + HANDLE && mouseY < wy1 - HANDLE;

            if (near(mouseX, wx0) && near(mouseY, wy0)) {
                drag = 3;
            } else if (near(mouseX, wx1) && near(mouseY, wy0)) {
                drag = 4;
            } else if (near(mouseX, wx0) && near(mouseY, wy1)) {
                drag = 5;
            } else if (near(mouseX, wx1) && near(mouseY, wy1)) {
                drag = 6;
            } else if (near(mouseX, wx0) && insideY) {
                drag = 7;
            } else if (near(mouseX, wx1) && insideY) {
                drag = 8;
            } else if (near(mouseY, wy0) && insideX) {
                drag = 9;
            } else if (near(mouseY, wy1) && insideX) {
                drag = 10;
            } else if (insideX && insideY) {
                drag = 1;
                grabDu = toU(mouseX) - u0;
                grabDv = toV(mouseY) - v0;
            } else {
                // Empty preview area: start drawing a fresh window from here.
                drag = 2;
                newAnchorU = clamp01(toU(mouseX));
                newAnchorV = clamp01(toV(mouseY));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (drag == 0 || button != 0) {
            return super.mouseDragged(mouseX, mouseY, button, dx, dy);
        }
        float mu = (float) clamp01(toU(mouseX));
        float mv = (float) clamp01(toV(mouseY));
        float w = u1 - u0;
        float h = v1 - v0;
        switch (drag) {
            case 1 -> { // move, size preserved
                float nu0 = (float) clamp(toU(mouseX) - grabDu, 0.0, 1.0 - w);
                float nv0 = (float) clamp(toV(mouseY) - grabDv, 0.0, 1.0 - h);
                u0 = nu0;
                v0 = nv0;
                u1 = nu0 + w;
                v1 = nv0 + h;
            }
            case 2 -> { // draw new window from the anchor
                u0 = (float) Math.min(newAnchorU, mu);
                u1 = (float) Math.max(newAnchorU, mu);
                v0 = (float) Math.min(newAnchorV, mv);
                v1 = (float) Math.max(newAnchorV, mv);
            }
            case 3 -> {
                u0 = Math.min(mu, u1 - CropWindow.MIN_SIZE);
                v0 = Math.min(mv, v1 - CropWindow.MIN_SIZE);
            }
            case 4 -> {
                u1 = Math.max(mu, u0 + CropWindow.MIN_SIZE);
                v0 = Math.min(mv, v1 - CropWindow.MIN_SIZE);
            }
            case 5 -> {
                u0 = Math.min(mu, u1 - CropWindow.MIN_SIZE);
                v1 = Math.max(mv, v0 + CropWindow.MIN_SIZE);
            }
            case 6 -> {
                u1 = Math.max(mu, u0 + CropWindow.MIN_SIZE);
                v1 = Math.max(mv, v0 + CropWindow.MIN_SIZE);
            }
            case 7 -> u0 = Math.min(mu, u1 - CropWindow.MIN_SIZE);
            case 8 -> u1 = Math.max(mu, u0 + CropWindow.MIN_SIZE);
            case 9 -> v0 = Math.min(mv, v1 - CropWindow.MIN_SIZE);
            case 10 -> v1 = Math.max(mv, v0 + CropWindow.MIN_SIZE);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag != 0 && button == 0) {
            if (drag == 2) {
                // A fresh window that is too small snaps up to the minimum.
                if (u1 - u0 < CropWindow.MIN_SIZE) {
                    u1 = Math.min(1.0F, u0 + CropWindow.MIN_SIZE);
                    u0 = u1 - CropWindow.MIN_SIZE;
                }
                if (v1 - v0 < CropWindow.MIN_SIZE) {
                    v1 = Math.min(1.0F, v0 + CropWindow.MIN_SIZE);
                    v0 = v1 - CropWindow.MIN_SIZE;
                }
            }
            drag = 0;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
