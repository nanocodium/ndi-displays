package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateWinchConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kinetic winch configuration: video source and canvas slice, panel geometry, flight
 * envelope (min/max drop, speed, manual target) and — when Theatrical is installed —
 * the DMX patch (network / universe / address, 4-channel fixture).
 */
public class WinchConfigScreen extends Screen {

    private static final int[] PX_PER_BLOCK_PRESETS = {512, 384, 256, 208, 170, 128, 96, 64, 48, 32};
    private static final UUID NULL_UUID = new UUID(0, 0);

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

    private final KineticWinchBlockEntity winch;

    // Survives widget rebuilds (resize), so typed input is never lost.
    private String source;
    private String colsText;
    private String rowsText;
    private String colText;
    private String rowText;
    private String panelWText;
    private String panelHText;
    private String minDropText;
    private String maxDropText;
    private String universeText;
    private String addressText;

    private int pxPerBlock;
    private float brightness;
    private int pattern;
    private int orientation;
    private boolean mesh;
    private float speed;
    /** Manual target, normalised 0..1 over the min→max envelope. */
    private double targetNorm;
    private UUID networkId;

    private EditBox sourceBox;
    private NdiSourcePicker picker;

    public WinchConfigScreen(KineticWinchBlockEntity winch) {
        super(Component.translatable("gui.ndidisplays.winch.title"));
        this.winch = winch;
        this.source = winch.getSourceName();
        this.colsText = String.valueOf(winch.getCanvasCols());
        this.rowsText = String.valueOf(winch.getCanvasRows());
        this.colText = String.valueOf(winch.getCanvasCol());
        this.rowText = String.valueOf(winch.getCanvasRow());
        this.panelWText = String.valueOf(winch.getPanelWidth());
        this.panelHText = String.valueOf(winch.getPanelHeight());
        this.minDropText = format(winch.getMinDrop());
        this.maxDropText = format(winch.getMaxDrop());
        this.universeText = String.valueOf(winch.getDmxUniverse());
        this.addressText = String.valueOf(winch.getDmxAddress());
        this.pxPerBlock = closestPreset(winch.getPixelsPerBlock());
        this.brightness = winch.getBrightness();
        this.pattern = winch.getTestPattern();
        this.orientation = winch.getOrientation();
        this.mesh = winch.isMesh();
        this.speed = winch.getSpeed();
        float span = Math.max(0.01F, winch.getMaxDrop() - winch.getMinDrop());
        this.targetNorm = (winch.getTargetDrop() - winch.getMinDrop()) / span;
        this.networkId = winch.getNetworkId();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 20;

        sourceBox = new EditBox(font, left, y, 264, 18, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(KineticWinchBlockEntity.MAX_SOURCE_NAME);
        sourceBox.setValue(source);
        sourceBox.setResponder(value -> source = value);
        addRenderableWidget(sourceBox);
        y += 22;

        // Discovered NDI sources / live camera rigs, same picker as the LED wall GUI.
        picker = new NdiSourcePicker(2, this::addRenderableWidget, this::removeWidget, name -> {
            source = name;
            sourceBox.setValue(name);
        });
        picker.init(left, y);
        y += picker.height() + 12;

        // Canvas slice: total tile grid and this tile's cell within it.
        addNumBox(left, y, 60, colsText, v -> colsText = v);
        addNumBox(left + 66, y, 60, rowsText, v -> rowsText = v);
        addNumBox(left + 138, y, 60, colText, v -> colText = v);
        addNumBox(left + 204, y, 60, rowText, v -> rowText = v);
        y += 30;

        // Panel geometry.
        addNumBox(left, y, 60, panelWText, v -> panelWText = v);
        addNumBox(left + 66, y, 60, panelHText, v -> panelHText = v);
        addRenderableWidget(CycleButton.<Integer>builder(o ->
                        Component.translatable(o == KineticWinchBlockEntity.ORIENTATION_FLAT
                                ? "gui.ndidisplays.winch.flat" : "gui.ndidisplays.winch.vertical"))
                .withValues(List.of(0, 1))
                .withInitialValue(orientation)
                .displayOnlyValue()
                .create(left + 138, y, 60, 18, Component.translatable("gui.ndidisplays.winch.orientation"),
                        (btn, val) -> orientation = val));
        addRenderableWidget(CycleButton.onOffBuilder(mesh)
                .displayOnlyValue()
                .create(left + 204, y, 60, 18, Component.translatable("gui.ndidisplays.winch.mesh"),
                        (btn, val) -> mesh = val));
        y += 24;

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
        y += 20;

        addRenderableWidget(new FloatSlider(left, y, 130, brightness, 0.05, 1.0,
                v -> brightness = (float) v,
                v -> String.format("Brightness: %d%%", Math.round(v * 100))));
        addRenderableWidget(new FloatSlider(left + 134, y, 130, speed, 0.1, KineticWinchBlockEntity.MAX_SPEED,
                v -> speed = (float) v,
                v -> String.format("Speed: %.2f m/s", v)));
        y += 30;

        // Flight envelope + manual target.
        addNumBox(left, y, 60, minDropText, v -> minDropText = v);
        addNumBox(left + 66, y, 60, maxDropText, v -> maxDropText = v);
        addRenderableWidget(new FloatSlider(left + 138, y, 126, targetNorm, 0.0, 1.0,
                v -> targetNorm = v,
                v -> String.format("Target: %.2f m", parseF(minDropText, winch.getMinDrop())
                        + v * Math.max(0, parseF(maxDropText, winch.getMaxDrop())
                        - parseF(minDropText, winch.getMinDrop())))));
        y += 30;

        // DMX patch (address/universe always editable; the network picker needs Theatrical).
        addNumBox(left, y, 60, universeText, v -> universeText = v);
        addNumBox(left + 66, y, 60, addressText, v -> addressText = v);
        if (TheatricalCompat.LOADED) {
            Map<UUID, String> networks = TheatricalCompat.knownNetworks();
            List<UUID> values = new ArrayList<>();
            values.add(NULL_UUID);
            values.addAll(networks.keySet());
            if (!values.contains(networkId)) {
                values.add(networkId);
            }
            addRenderableWidget(CycleButton.<UUID>builder(id -> {
                        if (NULL_UUID.equals(id)) {
                            return Component.translatable("gui.ndidisplays.winch.no_network");
                        }
                        String name = networks.get(id);
                        return Component.literal(name != null ? name : "Unknown");
                    })
                    .withValues(values)
                    .withInitialValue(networkId)
                    .displayOnlyValue()
                    .create(left + 138, y, 126, 18, Component.translatable("gui.ndidisplays.winch.network"),
                            (btn, val) -> networkId = val));
        }
        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    private void addNumBox(int x, int y, int w, String initial, java.util.function.Consumer<String> out) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
        box.setValue(initial);
        box.setResponder(out::accept);
        addRenderableWidget(box);
    }

    private void apply() {
        float minDrop = parseF(minDropText, winch.getMinDrop());
        float maxDrop = Math.max(minDrop, parseF(maxDropText, winch.getMaxDrop()));
        float target = minDrop + (float) targetNorm * (maxDrop - minDrop);
        NetworkHandler.CHANNEL.sendToServer(new UpdateWinchConfigPacket(
                winch.getBlockPos(),
                sourceBox.getValue().trim(),
                pxPerBlock,
                brightness,
                pattern,
                parseI(colsText, winch.getCanvasCols()),
                parseI(rowsText, winch.getCanvasRows()),
                parseI(colText, winch.getCanvasCol()),
                parseI(rowText, winch.getCanvasRow()),
                parseI(panelWText, winch.getPanelWidth()),
                parseI(panelHText, winch.getPanelHeight()),
                orientation,
                mesh,
                minDrop,
                maxDrop,
                speed,
                target,
                parseI(universeText, winch.getDmxUniverse()),
                parseI(addressText, winch.getDmxAddress()),
                networkId));
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
        graphics.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        labels(graphics, left, 80, "Canvas W", "Canvas H", "My Col", "My Row");
        labels(graphics, left, 110, "Panel W", "Panel H", "Orient", "Mesh");
        labels(graphics, left, 184, "Min m", "Max m", "", "");
        labels(graphics, left, 214, "Universe", "Address",
                TheatricalCompat.LOADED ? "DMX Network" : "", "");
        graphics.drawString(font, NdiManager.getStatus(), left, height - 16,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060);
        if (!TheatricalCompat.LOADED) {
            graphics.drawString(font,
                    Component.translatable("gui.ndidisplays.winch.no_theatrical"),
                    left + 138, 218, 0x808080);
        }
        if (picker != null) {
            picker.renderScrollbar(graphics);
        }
    }

    private void labels(GuiGraphics graphics, int left, int y, String a, String b, String c, String d) {
        graphics.drawString(font, a, left, y, 0xA0A0A0);
        graphics.drawString(font, b, left + 66, y, 0xA0A0A0);
        graphics.drawString(font, c, left + 138, y, 0xA0A0A0);
        graphics.drawString(font, d, left + 204, y, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String format(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static int parseI(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseF(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
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
