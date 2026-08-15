package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.WallScanner;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateWallConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Wall configuration screen ("LED processor"). Opened by right-clicking any panel;
 * edits and applies settings to the whole detected wall.
 */
public class PanelConfigScreen extends Screen {

    /** Common rental-wall pixel pitches, stored as pixels per metre (block = 1 m). */
    private static final int[] PX_PER_BLOCK_PRESETS = {512, 384, 256, 208, 170, 128, 96, 64, 48, 32};

    private static final Component[] PATTERN_NAMES = {
            Component.translatable("gui.ndidisplays.pattern.video"),
            Component.translatable("gui.ndidisplays.pattern.bars"),
            Component.translatable("gui.ndidisplays.pattern.grid"),
            Component.translatable("gui.ndidisplays.pattern.white"),
            Component.translatable("gui.ndidisplays.pattern.red"),
            Component.translatable("gui.ndidisplays.pattern.green"),
            Component.translatable("gui.ndidisplays.pattern.blue"),
            Component.translatable("gui.ndidisplays.pattern.checker")
    };

    /** Visible rows in the source picker; the full list scrolls behind them. */
    private static final int SOURCE_ROWS = 3;
    private static final int SOURCE_ROW_HEIGHT = 18;

    private final LedPanelBlockEntity anchor;

    private EditBox sourceBox;
    /** Survives widget rebuilds (resize, list refresh) so typed input is never lost. */
    private String source;
    private final List<Button> sourceButtons = new ArrayList<>();
    private List<String> allNames = new ArrayList<>();
    private List<String> cameraNames = new ArrayList<>();
    private int sourceScroll;
    private int listLeft;
    private int listY;
    private int ticks;
    private int pxPerBlock;
    private float brightness;
    private float gamma;
    private int pattern;

    public PanelConfigScreen(LedPanelBlockEntity anchor) {
        super(Component.translatable("gui.ndidisplays.config.title"));
        this.anchor = anchor;
        this.source = anchor.getSourceName();
        // Snap to the preset the button will actually display, so what is shown is what
        // Apply sends — otherwise the label says P3.9 while a stored 250 px/m is applied.
        this.pxPerBlock = closestPreset(anchor.getPixelsPerBlock());
        this.brightness = anchor.getBrightness();
        this.gamma = anchor.getGamma();
        this.pattern = anchor.getTestPattern();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 130;
        int y = 40;

        sourceBox = new EditBox(font, left, y, 260, 20, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(LedPanelBlockEntity.MAX_SOURCE_NAME);
        // Restore from our own field, not from the block entity: init() runs again on every
        // resize, and re-reading the panel would silently wipe a half-typed source name.
        sourceBox.setValue(source);
        sourceBox.setResponder(value -> source = value);
        addRenderableWidget(sourceBox);
        y += 24;

        listLeft = left;
        listY = y;
        refreshNames();
        rebuildSourceButtons();
        y += SOURCE_ROWS * SOURCE_ROW_HEIGHT + 6;

        addRenderableWidget(CycleButton.<Integer>builder(px -> Component.literal(pitchLabel(px)))
                .withValues(boxed(PX_PER_BLOCK_PRESETS))
                .withInitialValue(pxPerBlock)
                .displayOnlyValue()
                .create(left, y, 128, 20, Component.translatable("gui.ndidisplays.pitch"),
                        (btn, val) -> pxPerBlock = val));

        addRenderableWidget(CycleButton.<Integer>builder(idx -> PATTERN_NAMES[idx])
                .withValues(range(PATTERN_NAMES.length))
                .withInitialValue(pattern)
                .create(left + 132, y, 128, 20, Component.translatable("gui.ndidisplays.pattern"),
                        (btn, val) -> pattern = val));
        y += 24;

        addRenderableWidget(new FloatSlider(left, y, 128, brightness, 0.05, 1.0,
                v -> brightness = (float) v,
                v -> String.format("Brightness: %d%% (%d nits)", Math.round(v * 100), Math.round(v * 5500))));

        addRenderableWidget(new FloatSlider(left + 132, y, 128, gamma, 1.8, 2.8,
                v -> gamma = (float) v,
                v -> String.format("Gamma: %.2f", v)));
        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.screen_dmx.open"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new ScreenDmxSlotsScreen(anchor, this)))
                .bounds(left, y, 128, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.processor.open"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new VideoProcessorScreen(this, anchor.getBlockPos(),
                                        source.trim(), anchor.crop())))
                .bounds(left + 132, y, 128, 20).build());
        y += 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"), b -> apply())
                .bounds(cx - 130, y, 128, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 128, 20).build());
    }

    /**
     * In-game camera rigs first (always selectable, even before network discovery
     * catches up), then other discovered NDI sources.
     */
    private void refreshNames() {
        List<String> cameras = dev.nano.ndidisplays.client.CameraFeedManager.getLiveCameraNames();
        List<String> names = new ArrayList<>(cameras);
        // Web terminals are local senders like the rigs, so they can appear before NDI
        // discovery has caught up with them.
        for (String web : dev.nano.ndidisplays.client.CameraFeedManager.getWebTerminalNames()) {
            if (names.stream().noneMatch(web::contains)) {
                names.add(web);
            }
        }
        for (String discovered : NdiManager.getSourceNames()) {
            boolean isCamera = cameras.stream().anyMatch(discovered::contains);
            if (!isCamera) {
                names.add(discovered);
            }
        }
        cameraNames = cameras;
        allNames = names;
    }

    private void rebuildSourceButtons() {
        sourceButtons.forEach(this::removeWidget);
        sourceButtons.clear();
        int maxScroll = Math.max(0, allNames.size() - SOURCE_ROWS);
        sourceScroll = Math.min(Math.max(sourceScroll, 0), maxScroll);
        for (int i = 0; i < SOURCE_ROWS; i++) {
            int index = sourceScroll + i;
            final String name = index < allNames.size() ? allNames.get(index) : null;
            final boolean isCamera = name != null && cameraNames.contains(name);
            Button b = Button.builder(
                            Component.literal(name != null ? (isCamera ? "§c●§r " + name : name) : "—"),
                            btn -> {
                                if (name != null) {
                                    source = name;
                                    sourceBox.setValue(name);
                                }
                            })
                    .bounds(listLeft, listY + i * SOURCE_ROW_HEIGHT, 218, 16).build();
            b.active = name != null;
            sourceButtons.add(b);
            addRenderableWidget(b);
        }
        // Manual refresh — rebuilds only the picker, so typed source text is preserved.
        Button refresh = Button.builder(Component.literal("⟳"), btn -> {
                    refreshNames();
                    rebuildSourceButtons();
                })
                .bounds(listLeft + 228, listY, 32, 16).build();
        sourceButtons.add(refresh);
        addRenderableWidget(refresh);
    }

    /** The source list follows discovery while the screen is open. */
    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) {
            List<String> before = allNames;
            refreshNames();
            if (!allNames.equals(before)) {
                rebuildSourceButtons();
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= listLeft && mouseX <= listLeft + 226
                && mouseY >= listY && mouseY <= listY + SOURCE_ROWS * SOURCE_ROW_HEIGHT
                && allNames.size() > SOURCE_ROWS) {
            sourceScroll -= (int) Math.signum(delta);
            rebuildSourceButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateWallConfigPacket(
                anchor.getBlockPos(), sourceBox.getValue().trim(), pxPerBlock, brightness, gamma, pattern));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        drawNativeResolution(graphics,
                dev.nano.ndidisplays.block.NativeResolution.of(anchor),
                sourceBox == null ? source : sourceBox.getValue(), height - 44);
        super.render(graphics, mouseX, mouseY, partialTick);

        WallScanner.WallInfo wall = anchor.getWallInfo();
        String header = wall == null ? "" : String.format("%d×%d m wall — %d×%d px",
                wall.width(), wall.height(), wall.width() * pxPerBlock, wall.height() * pxPerBlock);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(font, header, width / 2, 26, 0xA0A0A0);
        graphics.drawString(font, NdiManager.getStatus(), width / 2 - 130, height - 20,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060);

        // Scrollbar for the source picker, shown when the list overflows its rows.
        int total = allNames.size();
        if (total > SOURCE_ROWS) {
            int trackX = listLeft + 220;
            int trackY = listY;
            int trackH = SOURCE_ROWS * SOURCE_ROW_HEIGHT - 2;
            graphics.fill(trackX, trackY, trackX + 4, trackY + trackH, 0xFF202020);
            int thumbH = Math.max(8, trackH * SOURCE_ROWS / total);
            int thumbY = trackY + (trackH - thumbH) * sourceScroll / Math.max(1, total - SOURCE_ROWS);
            graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFFA0A0A0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String pitchLabel(int pxPerMetre) {
        return String.format("P%.1f — %d px/m", 1000.0F / pxPerMetre, pxPerMetre);
    }

    private static Integer closestPreset(int value) {
        int best = PX_PER_BLOCK_PRESETS[0];
        for (int p : PX_PER_BLOCK_PRESETS) {
            if (Math.abs(p - value) < Math.abs(best - value)) {
                best = p;
            }
        }
        return best;
    }

    private static List<Integer> boxed(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) {
            list.add(v);
        }
        return list;
    }

    private static List<Integer> range(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        return list;
    }

    private interface DoubleConsumer {
        void accept(double v);
    }

    private interface LabelFormatter {
        String label(double v);
    }

    private static class FloatSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final DoubleConsumer out;
        private final LabelFormatter fmt;

        FloatSlider(int x, int y, int w, double initial, double min, double max,
                    DoubleConsumer out, LabelFormatter fmt) {
            super(x, y, w, 20, Component.empty(), (initial - min) / (max - min));
            this.min = min;
            this.max = max;
            this.out = out;
            this.fmt = fmt;
            updateMessage();
        }

        private double actual() {
            return min + value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(fmt.label(actual())));
        }

        @Override
        protected void applyValue() {
            out.accept(actual());
        }
    }

    /**
     * Reports the screen's native resolution, the resolution to actually feed it, and how the
     * stream that is arriving compares.
     *
     * A native size is only useful if a source can produce it, so when the screen is finer than
     * one NDI feed can carry this also names the pitch that brings it back in range — the number
     * the operator would otherwise have to work out by hand.
     */
    private void drawNativeResolution(GuiGraphics graphics,
            dev.nano.ndidisplays.block.NativeResolution.Native res, String sourceName, int y) {
        int x = width / 2 - 132;
        boolean fits = res.fitsOneFeed();
        graphics.drawString(font, "Native: " + res.describe(), x, y,
                fits ? 0xFFD8DEE4 : 0xFFE0A050, false);

        String line;
        int colour;
        if (fits && !res.cropped()) {
            line = "Feed it: " + res.recommendedSource() + "  (1:1)";
            colour = 0xFF7ED08A;
        } else if (fits) {
            line = "Feed it: " + res.recommendedSource() + "  (1:1 through the crop)";
            colour = 0xFF7ED08A;
        } else {
            // Too fine for one feed: name both halves of the fix, since the resolution only
            // becomes correct once the pitch matches it.
            line = "Too large — feed it " + res.recommendedSource()
                    + " at pitch " + res.suggestedPitch();
            colour = 0xFFE0A050;
        }
        graphics.drawString(font, line, x, y + 10, colour, false);

        String verdict = null;
        if (sourceName != null && !sourceName.isBlank()) {
            dev.nano.ndidisplays.client.ndi.NdiStream stream =
                    dev.nano.ndidisplays.client.ndi.NdiManager.acquire(sourceName);
            if (stream != null) {
                verdict = dev.nano.ndidisplays.block.NativeResolution.compare(
                        res, stream.getVideoWidth(), stream.getVideoHeight());
            }
        }
        if (verdict != null) {
            graphics.drawString(font, "Incoming: " + verdict, x, y + 20,
                    verdict.startsWith("1:1") ? 0xFF7ED08A : 0xFFE0A050, false);
        } else {
            graphics.drawString(font, "Incoming: no signal", x, y + 20, 0xFF808A90, false);
        }
    }
}
