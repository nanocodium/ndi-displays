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
    private float offset;
    private float yOffset;
    private boolean convex;
    private int videoRepeat;
    private boolean hideMount;

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
        this.hideMount = screen.isMountHidden();
        this.screenHeight = screen.getScreenHeight();
        this.offset = screen.getOffset();
        this.yOffset = screen.getYOffset();
        this.convex = screen.isConvex();
        this.videoRepeat = screen.getVideoRepeat();
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

        // Radius and height run 0.5 m to 256 m: nine doublings, so both sliders are
        // logarithmic — linear would jump about 2 m per pixel and lose the small sizes.
        addRenderableWidget(new FloatSlider(left, y, 130, Math.log(radius),
                Math.log(CurvedScreenBlockEntity.MIN_RADIUS), Math.log(CurvedScreenBlockEntity.MAX_RADIUS),
                v -> radius = snapMetres(Math.exp(v), CurvedScreenBlockEntity.MIN_RADIUS,
                        CurvedScreenBlockEntity.MAX_RADIUS),
                v -> "Radius: " + fmtMetres(snapMetres(Math.exp(v), CurvedScreenBlockEntity.MIN_RADIUS,
                        CurvedScreenBlockEntity.MAX_RADIUS)) + " m"));
        addRenderableWidget(new FloatSlider(left + 134, y, 130, arcAngle,
                CurvedScreenBlockEntity.MIN_ANGLE, CurvedScreenBlockEntity.MAX_ANGLE,
                v -> arcAngle = (float) v,
                v -> v >= 359.5 ? "Angle: 360\u00B0 (cylinder)" : String.format("Angle: %.0f\u00B0", v)));
        y += 22;

        addRenderableWidget(new FloatSlider(left, y, 130, Math.log(screenHeight),
                Math.log(CurvedScreenBlockEntity.MIN_HEIGHT), Math.log(CurvedScreenBlockEntity.MAX_HEIGHT),
                v -> screenHeight = snapMetres(Math.exp(v), CurvedScreenBlockEntity.MIN_HEIGHT,
                        CurvedScreenBlockEntity.MAX_HEIGHT),
                v -> "Height: " + fmtMetres(snapMetres(Math.exp(v), CurvedScreenBlockEntity.MIN_HEIGHT,
                        CurvedScreenBlockEntity.MAX_HEIGHT)) + " m"));
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
        y += 22;

        // How many times the source frame tiles around the arc (1x = stretched once).
        addRenderableWidget(CycleButton.<Integer>builder(n ->
                        Component.literal(n == 1
                                ? "1\u00D7 (stretch)"
                                : n + "\u00D7"))
                .withValues(rangeFrom1(CurvedScreenBlockEntity.MAX_REPEAT))
                .withInitialValue(Math.min(videoRepeat, CurvedScreenBlockEntity.MAX_REPEAT))
                .create(left, y, 130, 18, Component.translatable("gui.ndidisplays.curved.repeat"),
                        (btn, val) -> videoRepeat = val));
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.processor.open"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                                new VideoProcessorScreen(this, screen.getBlockPos(),
                                        source.trim(), screen.crop())))
                .bounds(left + 134, y, 130, 18).build());
        y += 22;

        // Hide the centre hub so only the arc shows (a column with an empty core, a floating arc).
        addRenderableWidget(CycleButton.<Boolean>builder(val -> val
                        ? Component.translatable("gui.ndidisplays.curved.mount_hidden")
                        : Component.translatable("gui.ndidisplays.curved.mount_shown"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(hideMount)
                .create(left, y, 130, 18, Component.translatable("gui.ndidisplays.curved.mount"),
                        (btn, val) -> hideMount = val));
        // Distance from the mount to the arc's centre. Square-law so the first metres are
        // fine-grained while the far end still reaches 512 m; unlike radius it may be 0.
        double sqrtMax = Math.sqrt(CurvedScreenBlockEntity.MAX_OFFSET);
        addRenderableWidget(new FloatSlider(left + 134, y, 130, Math.sqrt(offset), 0.0, sqrtMax,
                v -> offset = snapMetres(v * v, 0.0F, CurvedScreenBlockEntity.MAX_OFFSET),
                v -> "Distance: " + fmtMetres(snapMetres(v * v, 0.0F, CurvedScreenBlockEntity.MAX_OFFSET)) + " m"));
        y += 22;

        // Vertical shift of the arc. Signed square-law: fine around zero, ±256 m at the ends.
        double sqrtYMax = Math.sqrt(CurvedScreenBlockEntity.MAX_Y_OFFSET);
        addRenderableWidget(new FloatSlider(left, y, 130, Math.copySign(Math.sqrt(Math.abs(yOffset)), yOffset),
                -sqrtYMax, sqrtYMax,
                v -> yOffset = signedSquare(v),
                v -> "Height offset: " + fmtSigned(signedSquare(v)) + " m"));
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
                offset,
                yOffset,
                convex,
                videoRepeat,
                hideMount));
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
        drawNativeResolution(graphics,
                dev.nano.ndidisplays.block.NativeResolution.of(screen),
                sourceBox == null ? source : sourceBox.getValue(), height - 44);
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

    private static List<Integer> rangeFrom1(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
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

    /** Snaps to 0.1 m under 16 m, 0.5 m under 64 m and whole metres above; the sliders
     *  are logarithmic, so raw values carry meaningless fractions at the large end. */
    private static float snapMetres(double v, float min, float max) {
        double step = v < 16.0 ? 0.1 : v < 64.0 ? 0.5 : 1.0;
        return (float) Math.max(min, Math.min(max, Math.round(v / step) * step));
    }

    private static float signedSquare(double v) {
        float m = snapMetres(v * v, 0.0F, CurvedScreenBlockEntity.MAX_Y_OFFSET);
        return v < 0 ? -m : m;
    }

    private static String fmtSigned(float v) {
        return (v < 0 ? "-" : v > 0 ? "+" : "") + fmtMetres(Math.abs(v));
    }

    private static String fmtMetres(float v) {
        return v < 64.0F ? String.format("%.1f", v) : String.format("%.0f", v);
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
