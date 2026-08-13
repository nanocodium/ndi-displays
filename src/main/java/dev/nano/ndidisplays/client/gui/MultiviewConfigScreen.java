package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.MultiviewBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateMultiviewConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Multiview monitor configuration: layout (2x2/3x3), screen width, brightness, and
 * one source per cell. The NDI picker fills whichever cell box was focused last.
 */
public class MultiviewConfigScreen extends Screen {

    private final MultiviewBlockEntity monitor;

    private int layout;
    private float screenWidth;
    private float brightness;
    private final String[] sources = new String[MultiviewBlockEntity.MAX_CELLS];

    private final EditBox[] cellBoxes = new EditBox[MultiviewBlockEntity.MAX_CELLS];
    private NdiSourcePicker picker;
    private int focusedCell = 0;

    public MultiviewConfigScreen(MultiviewBlockEntity monitor) {
        super(Component.translatable("gui.ndidisplays.multiview.title"));
        this.monitor = monitor;
        this.layout = monitor.getLayout();
        this.screenWidth = monitor.getScreenWidth();
        this.brightness = monitor.getBrightness();
        for (int i = 0; i < sources.length; i++) {
            sources[i] = monitor.getSource(i);
        }
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 26;

        picker = new NdiSourcePicker(3, this::addRenderableWidget, this::removeWidget, name -> {
            sources[focusedCell] = name;
            cellBoxes[focusedCell].setValue(name);
        });
        picker.init(left, y);
        y += picker.height() + 10;

        addRenderableWidget(CycleButton.<Integer>builder(val -> Component.literal(
                        val == MultiviewBlockEntity.LAYOUT_3X3 ? "3x3" : "2x2"))
                .withValues(MultiviewBlockEntity.LAYOUT_2X2, MultiviewBlockEntity.LAYOUT_3X3)
                .withInitialValue(layout)
                .create(left, y, 84, 18, Component.translatable("gui.ndidisplays.multiview.layout"),
                        (btn, val) -> layout = val));
        addRenderableWidget(new FloatSlider(left + 90, y, 84, screenWidth,
                MultiviewBlockEntity.MIN_WIDTH, MultiviewBlockEntity.MAX_WIDTH,
                v -> screenWidth = (float) v,
                v -> String.format("Width: %.1f m", v)));
        addRenderableWidget(new FloatSlider(left + 180, y, 84, brightness, 0.05, 1.0,
                v -> brightness = (float) v,
                v -> String.format("Bright: %d%%", Math.round(v * 100))));
        y += 26;

        // 3x3 grid of cell source boxes; in 2x2 layout only cells 1-4 are used.
        for (int i = 0; i < MultiviewBlockEntity.MAX_CELLS; i++) {
            int col = i % 3;
            int row = i / 3;
            final int cell = i;
            EditBox box = new EditBox(font, left + col * 90, y + row * 24, 84, 18,
                    Component.literal("Cell " + (i + 1)));
            box.setMaxLength(MultiviewBlockEntity.MAX_SOURCE_NAME);
            box.setValue(sources[i]);
            box.setResponder(value -> {
                sources[cell] = value;
                focusedCell = cell;
            });
            cellBoxes[i] = box;
            addRenderableWidget(box);
        }
        y += 3 * 24 + 6;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        for (int i = 0; i < cellBoxes.length; i++) {
            if (cellBoxes[i] != null && cellBoxes[i].isFocused()) {
                focusedCell = i;
            }
        }
        return handled;
    }

    private void apply() {
        String[] trimmed = new String[sources.length];
        for (int i = 0; i < sources.length; i++) {
            trimmed[i] = sources[i] == null ? "" : sources[i].trim();
        }
        NetworkHandler.CHANNEL.sendToServer(new UpdateMultiviewConfigPacket(
                monitor.getBlockPos(), layout, screenWidth, brightness, trimmed));
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
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        int left = width / 2 - 132;
        graphics.drawString(font, Component.translatable("gui.ndidisplays.multiview.hint",
                        focusedCell + 1), left, height - 28, 0xA0A0A0);
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
