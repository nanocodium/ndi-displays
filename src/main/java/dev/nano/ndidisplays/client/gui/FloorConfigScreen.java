package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.FloorScanner;
import dev.nano.ndidisplays.block.LedFloorBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateFloorConfigPacket;
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
 * LED floor processor: source, pitch, brightness, gamma, pattern — applied to
 * every tile of the detected floor rectangle.
 */
public class FloorConfigScreen extends Screen {

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

    private final LedFloorBlockEntity floor;

    private String source;
    private int pxPerBlock;
    private float brightness;
    private float gamma;
    private int pattern;

    private EditBox sourceBox;
    private NdiSourcePicker picker;

    public FloorConfigScreen(LedFloorBlockEntity floor) {
        super(Component.translatable("gui.ndidisplays.floor.title"));
        this.floor = floor;
        this.source = floor.getSourceName();
        this.pxPerBlock = closestPreset(floor.getPixelsPerBlock());
        this.brightness = floor.getBrightness();
        this.gamma = floor.getGamma();
        this.pattern = floor.getTestPattern();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 30;

        sourceBox = new EditBox(font, left, y, 264, 18, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(LedFloorBlockEntity.MAX_SOURCE_NAME);
        sourceBox.setValue(source);
        sourceBox.setResponder(value -> source = value);
        addRenderableWidget(sourceBox);
        y += 22;

        picker = new NdiSourcePicker(3, this::addRenderableWidget, this::removeWidget, name -> {
            source = name;
            sourceBox.setValue(name);
        });
        picker.init(left, y);
        y += picker.height() + 12;

        addRenderableWidget(CycleButton.<Integer>builder(px -> Component.literal(pitchLabel(px)))
                .withValues(boxed(PX_PER_BLOCK_PRESETS))
                .withInitialValue(pxPerBlock)
                .displayOnlyValue()
                .create(left, y, 130, 18, Component.translatable("gui.ndidisplays.pitch"),
                        (btn, val) -> pxPerBlock = val));
        addRenderableWidget(CycleButton.<Integer>builder(idx -> PATTERN_NAMES[idx])
                .withValues(range(PATTERN_NAMES.length))
                .withInitialValue(pattern)
                .displayOnlyValue()
                .create(left + 134, y, 130, 18, Component.translatable("gui.ndidisplays.pattern"),
                        (btn, val) -> pattern = val));
        y += 22;

        addRenderableWidget(new FloatSlider(left, y, 130, brightness, 0.05, 1.0,
                v -> brightness = (float) v,
                v -> String.format("Brightness: %d%%", Math.round(v * 100))));
        addRenderableWidget(new FloatSlider(left + 134, y, 130, gamma, 1.8, 2.8,
                v -> gamma = (float) v,
                v -> String.format("Gamma: %.2f", v)));
        y += 22;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.screen_dmx.open"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new ScreenDmxSlotsScreen(floor, this)))
                .bounds(left, y, 130, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.processor.open"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new VideoProcessorScreen(this, floor.getBlockPos(),
                                        source.trim(), floor.crop())))
                .bounds(left + 134, y, 130, 18).build());
        y += 26;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateFloorConfigPacket(
                floor.getBlockPos(), sourceBox.getValue().trim(), pxPerBlock, brightness, gamma, pattern));
        onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (picker != null) {
            picker.tick();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (picker != null && picker.mouseScrolled(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 132;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        FloorScanner.FloorInfo info = floor.getFloorInfo();
        if (info != null) {
            graphics.drawCenteredString(font, String.format("%d\u00D7%d m floor — %d\u00D7%d px",
                    info.width(), info.depth(), info.width() * pxPerBlock, info.depth() * pxPerBlock),
                    width / 2, 22, 0xA0A0A0);
        }
        graphics.drawString(font, NdiManager.getStatus(), left, height - 16,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060);
        if (picker != null) {
            picker.renderScrollbar(graphics);
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

    private interface DoubleOut {
        void accept(double v);
    }

    private interface LabelFmt {
        String label(double v);
    }

    private static class FloatSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final DoubleOut out;
        private final LabelFmt fmt;

        FloatSlider(int x, int y, int w, double initial, double min, double max,
                    DoubleOut out, LabelFmt fmt) {
            super(x, y, w, 18, Component.empty(), (initial - min) / (max - min));
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
}
