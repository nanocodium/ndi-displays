package dev.nano.ndidisplays.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.SwitcherBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.SwitcherActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * The switcher panel, laid out like the hardware it imitates: preview and program monitors up
 * top, a green preview bus and a red program bus below them, and the transition block on the
 * right — CUT, AUTO, the style keys and the rate. Pressing a preview key arms an input; AUTO
 * takes it to air through the selected transition; program-bus keys hot-cut, as they do on a
 * real desk. Input assignment: select an input key, then pick its NDI source from the list.
 */
public class SwitcherScreen extends Screen {

    private static final int C_PANEL = 0xFF17171D;
    private static final int C_KEY = 0xFF2A2A34;
    private static final int C_KEY_EDGE = 0xFF3C3C4A;
    private static final int C_RED = 0xFFD03030;
    private static final int C_GREEN = 0xFF30C050;
    private static final int C_AMBER = 0xFFE0A030;
    private static final int C_TEXT = 0xFFE8E8F0;
    private static final int C_DIM = 0xFF9AA0B0;

    private final SwitcherBlockEntity sw;

    private EditBox nameBox;
    private NdiSourcePicker picker;
    /** Input slot currently being assigned, or -1. */
    private int assigning = -1;

    private int busX;
    private int busW;
    private int pvwY;
    private int pgmY;
    private int keyW;
    private int transX;

    public SwitcherScreen(SwitcherBlockEntity sw) {
        super(Component.translatable("gui.ndidisplays.switcher.title"));
        this.sw = sw;
    }

    private void send(int op, int index, String text) {
        NetworkHandler.CHANNEL.sendToServer(new SwitcherActionPacket(sw.getBlockPos(), op, index, text));
        // applied locally too so the panel answers instantly; the server sync confirms
        sw.handleAction(op, index, text);
    }

    private int topPad = 8;

    @Override
    protected void init() {
        // Centre the whole desk in the window instead of hugging the top-left.
        busW = Math.min(430, width - 240);
        int transW = 148;
        int contentW = busW + 16 + transW;
        busX = Math.max(8, (width - contentW) / 2);
        keyW = (busW - 8 * 4) / 9;
        int monH = Math.max(70, (int) (height * 0.30));
        int contentH = monH + 26 + 40 + 46 + 130;
        topPad = Math.max(8, (height - contentH) / 2);
        int pad = topPad;
        pvwY = pad + monH + 26;
        pgmY = pvwY + 40;
        transX = busX + busW + 16;

        int ty = pvwY;
        // transition block
        addRenderableWidget(Button.builder(Component.literal("CUT"), b -> send(SwitcherBlockEntity.OP_CUT, 0, ""))
                .bounds(transX, ty, 62, 26).build());
        addRenderableWidget(Button.builder(Component.literal("AUTO"), b -> send(SwitcherBlockEntity.OP_AUTO, 0, ""))
                .bounds(transX + 66, ty, 62, 26).build());
        ty += 30;
        String[] styles = {"MIX", "DIP", "WIPE"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal(styles[i]),
                            b -> send(SwitcherBlockEntity.OP_STYLE, idx, ""))
                    .bounds(transX + i * 44, ty, 40, 18).build());
        }
        ty += 22;
        int[][] rates = {{10, 0}, {20, 1}, {40, 2}};
        String[] rateNames = {"0.5s", "1.0s", "2.0s"};
        for (int i = 0; i < 3; i++) {
            final int ticks = rates[i][0];
            addRenderableWidget(Button.builder(Component.literal(rateNames[i]),
                            b -> send(SwitcherBlockEntity.OP_RATE, ticks, ""))
                    .bounds(transX + i * 44, ty, 40, 18).build());
        }
        ty += 30;

        nameBox = new EditBox(font, transX, ty, 90, 18,
                Component.translatable("gui.ndidisplays.computer.name"));
        nameBox.setMaxLength(SwitcherBlockEntity.MAX_NAME);
        nameBox.setValue(sw.getName());
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.computer.rename"),
                        b -> send(SwitcherBlockEntity.OP_NAME, 0, nameBox.getValue().trim()))
                .bounds(transX + 94, ty, 44, 18).build());
        ty += 22;
        addRenderableWidget(CycleButton.<Integer>builder(r -> Component.literal(
                        SwitcherBlockEntity.RES_W[r] + "x" + SwitcherBlockEntity.RES_H[r]))
                .withValues(0, 1, 2)
                .withInitialValue(sw.getResolution())
                .displayOnlyValue()
                .create(transX, ty, 84, 18, Component.empty(),
                        (b, v) -> send(SwitcherBlockEntity.OP_RES, v, "")));
        addRenderableWidget(CycleButton.<Integer>builder(f -> Component.literal(f + "fps"))
                .withValues(24, 30, 50, 60)
                .withInitialValue(closestFps(sw.getFps()))
                .displayOnlyValue()
                .create(transX + 88, ty, 50, 18, Component.empty(),
                        (b, v) -> send(SwitcherBlockEntity.OP_FPS, v, "")));
        ty += 22;
        addRenderableWidget(CycleButton.onOffBuilder(sw.isBroadcasting())
                .create(transX, ty, 84, 18, Component.translatable("gui.ndidisplays.computer.ndi"),
                        (b, v) -> send(SwitcherBlockEntity.OP_BROADCAST, v ? 1 : 0, "")));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(transX + 88, ty, 50, 18).build());

        // source picker for input assignment
        picker = new NdiSourcePicker(3, this::addRenderableWidget, this::removeWidget, name -> {
            if (assigning >= 0) {
                send(SwitcherBlockEntity.OP_SOURCE, assigning, name);
                assigning = -1;
            }
        });
        picker.init(busX, pgmY + 46);
    }

    private static int closestFps(int fps) {
        int[] presets = {24, 30, 50, 60};
        int best = presets[0];
        for (int p : presets) {
            if (Math.abs(p - fps) < Math.abs(best - fps)) {
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ bus input

    private int keyAt(double mx, double my, int rowY) {
        if (my < rowY || my >= rowY + 30) {
            return Integer.MIN_VALUE;
        }
        for (int i = 0; i < 9; i++) {
            int x = busX + i * (keyW + 4);
            if (mx >= x && mx < x + keyW) {
                return i == 8 ? SwitcherBlockEntity.BLACK : i;
            }
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int pvw = keyAt(mx, my, pvwY);
        if (pvw != Integer.MIN_VALUE) {
            if (button == 1 && pvw != SwitcherBlockEntity.BLACK) {
                assigning = pvw; // right-click an input key: assign its source from the list
            } else {
                send(SwitcherBlockEntity.OP_PREVIEW, pvw, "");
            }
            return true;
        }
        int pgm = keyAt(mx, my, pgmY);
        if (pgm != Integer.MIN_VALUE) {
            send(SwitcherBlockEntity.OP_PROGRAM, pgm, "");
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (picker != null && picker.mouseScrolled(mx, my, delta)) {
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void tick() {
        super.tick();
        if (picker != null) {
            picker.tick();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(0, 0, width, height, C_PANEL);

        long gameTime = sw.getLevel() == null ? 0L : sw.getLevel().getGameTime();
        boolean inTrans = sw.transitioning(gameTime);
        float progress = sw.transitionProgress(gameTime, partialTick);

        // monitors: preview (green) and program (red)
        int pad = topPad;
        int monH = pvwY - pad - 26;
        int monW = (busW - 8) / 2;
        drawMonitor(g, busX, pad + 10, monW, monH - 10, sw.busSource(sw.getPreview()),
                "PREVIEW", C_GREEN);
        drawMonitor(g, busX + monW + 8, pad + 10, monW, monH - 10, sw.busSource(sw.getProgram()),
                "PROGRAM", C_RED);

        // buses
        g.drawString(font, "PREVIEW BUS   (right-click a key to assign its NDI source)",
                busX, pvwY - 10, C_DIM, false);
        drawBus(g, pvwY, sw.getPreview(), C_GREEN, false);
        g.drawString(font, "PROGRAM BUS", busX, pgmY - 10, C_DIM, false);
        drawBus(g, pgmY, sw.getProgram(), C_RED, inTrans);

        // transition state
        int barX = transX;
        int barY = pvwY - 10;
        g.drawString(font, sw.getEffectiveSourceName(), transX, topPad, C_DIM, false);
        String style = new String[]{"MIX", "DIP", "WIPE"}[sw.getStyle()];
        g.drawString(font, "Style: " + style + "   Rate: "
                        + String.format("%.1fs", sw.getRateTicks() / 20.0),
                barX, barY, C_TEXT, false);
        if (inTrans) {
            g.fill(barX, barY - 8, barX + (int) (128 * progress), barY - 3, C_AMBER);
        }

        if (assigning >= 0) {
            g.drawString(font, "Pick a source for input " + (assigning + 1) + " below:",
                    busX, pgmY + 34, C_AMBER, false);
        } else {
            g.drawString(font, "Sources:", busX, pgmY + 34, C_DIM, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (picker != null) {
            picker.renderScrollbar(g);
        }
        g.drawString(font, NdiManager.getStatus(), busX, height - 12,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060, false);
    }

    private void drawBus(GuiGraphics g, int y, int active, int colour, boolean flash) {
        for (int i = 0; i < 9; i++) {
            int x = busX + i * (keyW + 4);
            boolean isBlack = i == 8;
            int bus = isBlack ? SwitcherBlockEntity.BLACK : i;
            boolean lit = bus == active;
            int fill = lit ? (flash && (System.currentTimeMillis() / 250) % 2 == 0
                    ? C_AMBER : colour) : C_KEY;
            g.fill(x, y, x + keyW, y + 30, fill);
            g.renderOutline(x, y, keyW, 30, C_KEY_EDGE);
            String label = isBlack ? "BLK" : String.valueOf(i + 1);
            g.drawCenteredString(font, label, x + keyW / 2, y + 5, lit ? 0xFF101014 : C_TEXT);
            if (!isBlack) {
                String src = sw.getSource(i);
                g.drawCenteredString(font, src.isBlank() ? "—"
                                : font.plainSubstrByWidth(src, keyW - 4),
                        x + keyW / 2, y + 18, lit ? 0xFF20242C : C_DIM);
            }
        }
    }

    private void drawMonitor(GuiGraphics g, int x, int y, int w, int h, String source,
                             String label, int colour) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, colour);
        g.fill(x, y, x + w, y + h, 0xFF06060A);
        int tex = 0;
        if (!source.isBlank()) {
            NdiStream stream = NdiManager.acquire(source);
            if (stream != null) {
                stream.uploadIfNeeded();
                tex = stream.getTextureId();
            }
        }
        if (tex != 0) {
            RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, tex);
            RenderSystem.disableBlend();
            Matrix4f mat = g.pose().last().pose();
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            b.vertex(mat, x, y + h, 0).uv(0.0F, 1.0F).endVertex();
            b.vertex(mat, x + w, y + h, 0).uv(1.0F, 1.0F).endVertex();
            b.vertex(mat, x + w, y, 0).uv(1.0F, 0.0F).endVertex();
            b.vertex(mat, x, y, 0).uv(0.0F, 0.0F).endVertex();
            BufferUploader.drawWithShader(b.end());
        } else {
            g.drawCenteredString(font, source.isBlank() ? "BLACK" : "no signal",
                    x + w / 2, y + h / 2 - 4, C_DIM);
        }
        g.drawString(font, label, x + 3, y + 3, colour, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
