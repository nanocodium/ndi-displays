package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.CurvedScreenBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateCurvedScreenConfigPacket;
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
 * Curved screen configuration: the usual processor settings plus radius, opening
 * angle (360 = full cylinder), height and concave/convex side.
 */
public class CurvedScreenConfigScreen extends Screen {

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

    private final CurvedScreenBlockEntity screen;

    private String source;
    private int pxPerBlock;
    private float brightness;
    private int pattern;
    private float radius;
    private float arcAngle;
    private float screenHeight;
    private boolean convex;

    private EditBox sourceBox;
    private NdiSourcePicker picker;

    public CurvedScreenConfigScreen(CurvedScreenBlockEntity screen) {
        super(Component.translatable("gui.ndidisplays.curved.title"));
        this.screen = screen;
        this.source = screen.getSourceName();
        this.pxPerBlock = closestPreset(screen.getPixelsPerBlock());
        this.brightness = screen.getBrightness();
        this.pattern = screen.getTestPattern();
        this.radius = screen.getRadius();
        this.arcAngle = screen.getArcAngle();
        this.screenHeight = screen.getScreenHeight();
        this.convex = screen.isConvex();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 30;

        sourceBox = new EditBox(font, left, y, 264, 18, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(CurvedScreenBlockEntity.MAX_SOURCE_NAME);
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

        addRenderableWidget(new FloatSlider(left, y, 130, radius,
                CurvedScreenBlockEntity.MIN_RADIUS, CurvedScreenBlockEntity.MAX_RADIUS,
                v -> radius = (float) v,
                v -> String.format("Radius: %.1f m", v)));
        addRenderableWidget(new FloatSlider(left + 134, y, 130, arcAngle,
                CurvedScreenBlockEntity.MIN_ANGLE, CurvedScreenBlockEntity.MAX_ANGLE,
                v -> arcAngle = (float) v,
                v -> v >= 359.5 ? "Angle: 360\u00B0 (cylinder)" : String.format("Angle: %.0f\u00B0", v)));
        y += 22;

        addRenderableWidget(new FloatSlider(left, y, 130, screenHeight,
                CurvedScreenBlockEntity.MIN_HEIGHT, CurvedScreenBlockEntity.MAX_HEIGHT,
                v -> screenHeight = (float) v,
                v -> String.format("Height: %.1f m", v)));
        addRenderableWidget(new FloatSlider(left + 134, y, 130, brightness, 0.05, 1.0,
                v -> brightness = (float) v,
                v -> String.format("Brightness: %d%%", Math.round(v * 100))));
        y += 22;

        addRenderableWidget(CycleButton.<Boolean>builder(val -> val
                        ? Component.translatable("gui.ndidisplays.curved.convex")
                        : Component.translatable("gui.ndidisplays.curved.concave"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(convex)
                .displayOnlyValue()
                .create(left, y, 130, 18, Component.translatable("gui.ndidisplays.curved.side"),
                        (btn, val) -> convex = val));
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.screen_dmx.open"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new ScreenDmxSlotsScreen(screen, this)))
                .bounds(left + 134, y, 130, 18).build());
        y += 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateCurvedScreenConfigPacket(
                screen.getBlockPos(),
                sourceBox.getValue().trim(),
                pxPerBlock,
                brightness,
                pattern,
                radius,
                arcAngle,
                screenHeight,
                convex));
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
