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
    /** Twin mode: winch B's manual target, same normalisation. */
    private double targetBNorm;
    private boolean twinMode;
    private float maxTilt;
    private int payload;
    private int fixtureMode;
    private java.util.List<dev.nano.ndidisplays.compat.theatrical.FixturePersonality> personalities = java.util.List.of();
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
        this.targetBNorm = (winch.getTargetDropB() - winch.getMinDrop()) / span;
        this.twinMode = winch.isTwinMode();
        this.maxTilt = winch.getMaxTilt();
        this.payload = winch.getPayload();
        this.fixtureMode = winch.getFixtureMode();
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

        // Flight envelope + manual target (winch A — the only motor in LINKED mode).
        addNumBox(left, y, 60, minDropText, v -> minDropText = v);
        addNumBox(left + 66, y, 60, maxDropText, v -> maxDropText = v);
        addRenderableWidget(new FloatSlider(left + 138, y, 126, targetNorm, 0.0, 1.0,
                v -> targetNorm = v,
                v -> String.format("Target A: %.2f m", parseF(minDropText, winch.getMinDrop())
                        + v * Math.max(0, parseF(maxDropText, winch.getMaxDrop())
                        - parseF(minDropText, winch.getMinDrop())))));
        y += 30;

        // Twin winch mode: the two cables become independent motors and the tile tilts.
        addRenderableWidget(CycleButton.<Boolean>builder(twin ->
                        Component.translatable(twin ? "gui.ndidisplays.winch.mode.twin"
                                : "gui.ndidisplays.winch.mode.linked"))
                .withValues(List.of(Boolean.FALSE, Boolean.TRUE))
                .withInitialValue(twinMode)
                .displayOnlyValue()
                .create(left, y, 60, 18, Component.translatable("gui.ndidisplays.winch.mode"),
                        (btn, val) -> twinMode = val));
        addRenderableWidget(new FloatSlider(left + 66, y, 66, maxTilt,
                0.0, KineticWinchBlockEntity.MAX_TILT_LIMIT,
                v -> maxTilt = (float) v,
                v -> String.format("%.0f\u00B0", v)));
        addRenderableWidget(new FloatSlider(left + 138, y, 126, targetBNorm, 0.0, 1.0,
                v -> targetBNorm = v,
                v -> String.format("Target B: %.2f m", parseF(minDropText, winch.getMinDrop())
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

        // What hangs from the hook: video tile, kinetic sphere, mirror ball or a
        // Theatrical fixture (set by right-clicking the winch with the fixture item).
        addRenderableWidget(CycleButton.<Integer>builder(p -> Component.translatable(switch (p) {
                    case KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE -> "gui.ndidisplays.winch.payload.sphere";
                    case KineticWinchBlockEntity.PAYLOAD_MIRROR_BALL -> "gui.ndidisplays.winch.payload.mirror";
                    case KineticWinchBlockEntity.PAYLOAD_FIXTURE -> "gui.ndidisplays.winch.payload.fixture";
                    case KineticWinchBlockEntity.PAYLOAD_SLAT -> "gui.ndidisplays.winch.payload.slat";
                    default -> "gui.ndidisplays.winch.payload.tile";
                }))
                .withValues(range(KineticWinchBlockEntity.PAYLOAD_COUNT))
                .withInitialValue(payload)
                .displayOnlyValue()
                .create(left, y, 130, 18, Component.translatable("gui.ndidisplays.winch.payload"),
                        (btn, val) -> payload = val));

        // Fixture Config: the DMX mode the flown fixture is patched in. When the fixture
        // declares its own personalities these are *its* modes, named as it names them
        // ("7ch - Standard"), and the label shows the winch's total footprint since the winch
        // prepends its own height/speed channels. Only when the fixture declares nothing do
        // we fall back to generic nested footprints.
        personalities = winch.fixturePersonalities();
        int modeCount = personalities.isEmpty()
                ? KineticWinchBlockEntity.FIXTURE_MODE_COUNT : personalities.size();
        if (fixtureMode >= modeCount) {
            fixtureMode = 0;
        }
        addRenderableWidget(CycleButton.<Integer>builder(m -> fixtureModeLabel(m))
                .withValues(range(modeCount))
                .withInitialValue(fixtureMode)
                .displayOnlyValue()
                .create(cx + 2, y, 130, 18,
                        Component.translatable("gui.ndidisplays.winch.fixture_mode"),
                        (btn, val) -> fixtureMode = val));
        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    /**
     * The fixture's own name for a mode plus the winch's total footprint, since the winch
     * prepends height/speed to whatever the fixture occupies. Falls back to the generic
     * footprints when the flown fixture declares no modes.
     */
    private Component fixtureModeLabel(int mode) {
        if (!personalities.isEmpty()) {
            var p = personalities.get(Math.floorMod(mode, personalities.size()));
            int total = KineticWinchBlockEntity.WINCH_LEAD_CHANNELS + p.channelCount();
            return Component.literal(p.description() + " (" + total + ")");
        }
        return Component.translatable(switch (mode) {
            case KineticWinchBlockEntity.FIXTURE_MODE_BASIC -> "gui.ndidisplays.winch.fixture_mode.basic";
            case KineticWinchBlockEntity.FIXTURE_MODE_COLOUR -> "gui.ndidisplays.winch.fixture_mode.colour";
            default -> "gui.ndidisplays.winch.fixture_mode.full";
        });
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
        float targetB = minDrop + (float) targetBNorm * (maxDrop - minDrop);
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
                twinMode,
                maxTilt,
                targetB,
                payload,
                fixtureMode,
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
        drawNativeResolution(graphics,
                dev.nano.ndidisplays.block.NativeResolution.ofCanvas(winch),
                sourceBox == null ? source : sourceBox.getValue(), height - 44);
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = width / 2 - 132;
        graphics.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        labels(graphics, left, 80, "Canvas W", "Canvas H", "My Col", "My Row");
        labels(graphics, left, 110, "Panel W", "Panel H", "Orient", "Mesh");
        labels(graphics, left, 184, "Min m", "Max m", "", "");
        labels(graphics, left, 214, "Mode", "Max tilt", "", "");
        labels(graphics, left, 244, "Universe", "Address",
                TheatricalCompat.LOADED ? "DMX Network" : "", "");
        labels(graphics, left, 268, "Payload", "", "", "");
        if (payload == KineticWinchBlockEntity.PAYLOAD_FIXTURE) {
            String id = winch.getFixtureBlockId();
            String shown = id.isEmpty()
                    ? Component.translatable("gui.ndidisplays.winch.payload.no_fixture").getString()
                    : id.substring(id.indexOf(':') + 1);
            graphics.drawString(font, shown, left, 296, id.isEmpty() ? 0xE06060 : 0xA0A0A0);
        }
        graphics.drawString(font, NdiManager.getStatus(), left, height - 16,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060);
        if (!TheatricalCompat.LOADED) {
            graphics.drawString(font,
                    Component.translatable("gui.ndidisplays.winch.no_theatrical"),
                    left + 138, 248, 0x808080);
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
