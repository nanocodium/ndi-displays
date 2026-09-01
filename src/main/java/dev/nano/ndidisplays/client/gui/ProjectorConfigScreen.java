package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.ProjectorBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateProjectorConfigPacket;
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
 * The projector's lens desk: source, aim, optics, geometry correction and blend — every slider
 * applies to the client immediately, so the beam moves live in the world behind the GUI while
 * it is trimmed, the way alignment actually gets done. Apply commits to the server; Cancel puts
 * the projector back exactly as found.
 */
public class ProjectorConfigScreen extends Screen {

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

    private static final float[] ASPECTS = {16.0F / 9.0F, 4.0F / 3.0F, 1.0F, 21.0F / 9.0F};
    private static final String[] ASPECT_NAMES = {"16:9", "4:3", "1:1", "21:9"};

    private final ProjectorBlockEntity projector;

    // Working copy; every change is previewed live on the client block entity.
    private String source;
    private int pattern;
    private float yaw;
    private float pitch;
    private float fov;
    private int aspectIdx;
    private float near;
    private float far;
    private float keystoneH;
    private float keystoneV;
    private float shiftH;
    private float shiftV;
    private float brightness;
    private float feather;
    private boolean additive;
    private boolean showFrustum;

    /** Snapshot for Cancel: the configuration as it was when the desk was opened. */
    private final UpdateProjectorConfigPacket original;

    private EditBox sourceBox;
    private NdiSourcePicker picker;
    private boolean committed;

    public ProjectorConfigScreen(ProjectorBlockEntity projector) {
        super(Component.translatable("gui.ndidisplays.projector.title"));
        this.projector = projector;
        this.source = projector.getSourceName();
        this.pattern = projector.getTestPattern();
        this.yaw = projector.getYaw();
        this.pitch = projector.getPitch();
        this.fov = projector.getFov();
        this.aspectIdx = closestAspect(projector.getAspect());
        this.near = projector.getNear();
        this.far = projector.getFar();
        this.keystoneH = projector.getKeystoneH();
        this.keystoneV = projector.getKeystoneV();
        this.shiftH = projector.getShiftH();
        this.shiftV = projector.getShiftV();
        this.brightness = projector.getBrightness();
        this.feather = projector.getFeather();
        this.additive = projector.isAdditive();
        this.showFrustum = projector.showFrustum();
        this.original = snapshot();
    }

    private UpdateProjectorConfigPacket snapshot() {
        return new UpdateProjectorConfigPacket(projector.getBlockPos(), source, pattern, yaw,
                pitch, fov, ASPECTS[aspectIdx], near, far, keystoneH, keystoneV, shiftH, shiftV,
                brightness, feather, additive, showFrustum);
    }

    /** Live preview: push the working copy into the client block entity every change. */
    private void preview() {
        projector.applyConfig(source, pattern, yaw, pitch, fov, ASPECTS[aspectIdx], near, far,
                keystoneH, keystoneV, shiftH, shiftV, brightness, feather, additive, showFrustum);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 200;
        int col = 130;
        int gap = 4;
        int y = 28;

        sourceBox = new EditBox(font, left, y, 264, 18, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(ProjectorBlockEntity.MAX_SOURCE_NAME);
        sourceBox.setValue(source);
        sourceBox.setResponder(value -> {
            source = value;
            preview();
        });
        addRenderableWidget(sourceBox);
        addRenderableWidget(CycleButton.<Integer>builder(idx -> PATTERN_NAMES[idx])
                .withValues(range(PATTERN_NAMES.length))
                .withInitialValue(pattern)
                .displayOnlyValue()
                .create(left + 268, y, col, 18, Component.translatable("gui.ndidisplays.pattern"),
                        (btn, val) -> {
                            pattern = val;
                            preview();
                        }));
        y += 22;

        picker = new NdiSourcePicker(3, this::addRenderableWidget, this::removeWidget, name -> {
            source = name;
            sourceBox.setValue(name);
            preview();
        });
        picker.init(left, y);
        y += picker.height() + 10;

        // ---- aim
        addRenderableWidget(slider(left, y, col, yaw, 0, 360,
                v -> yaw = (float) v, v -> String.format("Pan: %.1f°", v)));
        addRenderableWidget(slider(left + col + gap, y, col, pitch, -85, 85,
                v -> pitch = (float) v, v -> String.format("Tilt: %.1f°", v)));
        addRenderableWidget(slider(left + 2 * (col + gap), y, col, fov,
                ProjectorBlockEntity.MIN_FOV, ProjectorBlockEntity.MAX_FOV,
                v -> fov = (float) v, v -> String.format("Lens: %.0f° FOV", v)));
        y += 22;

        // ---- throw
        addRenderableWidget(slider(left, y, col, near,
                ProjectorBlockEntity.MIN_NEAR, ProjectorBlockEntity.MAX_NEAR,
                v -> near = (float) v, v -> String.format("Near: %.1f m", v)));
        addRenderableWidget(slider(left + col + gap, y, col, far,
                ProjectorBlockEntity.MIN_FAR, ProjectorBlockEntity.MAX_FAR,
                v -> far = (float) v, v -> String.format("Throw: %.0f m", v)));
        addRenderableWidget(CycleButton.<Integer>builder(idx -> Component.literal("Aspect " + ASPECT_NAMES[idx]))
                .withValues(range(ASPECTS.length))
                .withInitialValue(aspectIdx)
                .displayOnlyValue()
                .create(left + 2 * (col + gap), y, col, 18,
                        Component.translatable("gui.ndidisplays.projector.aspect"),
                        (btn, val) -> {
                            aspectIdx = val;
                            preview();
                        }));
        y += 22;

        // ---- geometry correction
        addRenderableWidget(slider(left, y, col, keystoneH,
                -ProjectorBlockEntity.MAX_KEYSTONE, ProjectorBlockEntity.MAX_KEYSTONE,
                v -> keystoneH = (float) v, v -> String.format("Keystone H: %.2f", v)));
        addRenderableWidget(slider(left + col + gap, y, col, keystoneV,
                -ProjectorBlockEntity.MAX_KEYSTONE, ProjectorBlockEntity.MAX_KEYSTONE,
                v -> keystoneV = (float) v, v -> String.format("Keystone V: %.2f", v)));
        addRenderableWidget(slider(left + 2 * (col + gap), y, col, feather,
                0, ProjectorBlockEntity.MAX_FEATHER,
                v -> feather = (float) v, v -> String.format("Soft edge: %.0f%%", v * 100)));
        y += 22;

        addRenderableWidget(slider(left, y, col, shiftH,
                -ProjectorBlockEntity.MAX_SHIFT, ProjectorBlockEntity.MAX_SHIFT,
                v -> shiftH = (float) v, v -> String.format("Shift H: %.2f", v)));
        addRenderableWidget(slider(left + col + gap, y, col, shiftV,
                -ProjectorBlockEntity.MAX_SHIFT, ProjectorBlockEntity.MAX_SHIFT,
                v -> shiftV = (float) v, v -> String.format("Shift V: %.2f", v)));
        addRenderableWidget(slider(left + 2 * (col + gap), y, col, brightness, 0.05, 1.0,
                v -> brightness = (float) v,
                v -> String.format("Brightness: %d%%", Math.round(v * 100))));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(additive)
                .create(left, y, col, 18,
                        Component.translatable("gui.ndidisplays.projector.additive"),
                        (btn, val) -> {
                            additive = val;
                            preview();
                        }));
        addRenderableWidget(CycleButton.onOffBuilder(showFrustum)
                .create(left + col + gap, y, col, 18,
                        Component.translatable("gui.ndidisplays.projector.frustum"),
                        (btn, val) -> {
                            showFrustum = val;
                            preview();
                        }));
        y += 26;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    private AbstractSliderButton slider(int x, int y, int w, double initial, double min,
                                        double max, DoubleOut out, LabelFmt fmt) {
        return new FloatSlider(x, y, w, initial, min, max, v -> {
            out.accept(v);
            preview();
        }, fmt);
    }

    private void apply() {
        committed = true;
        NetworkHandler.CHANNEL.sendToServer(new UpdateProjectorConfigPacket(
                projector.getBlockPos(), sourceBox.getValue().trim(), pattern, yaw, pitch, fov,
                ASPECTS[aspectIdx], near, far, keystoneH, keystoneV, shiftH, shiftV, brightness,
                feather, additive, showFrustum));
        onClose();
    }

    @Override
    public void onClose() {
        if (!committed) {
            // Cancel: undo the live preview so the projector is exactly as found.
            projector.applyConfig(original.source(), original.pattern(), original.yaw(),
                    original.pitch(), original.fov(), original.aspect(), original.near(),
                    original.far(), original.keystoneH(), original.keystoneV(),
                    original.shiftH(), original.shiftV(), original.brightness(),
                    original.feather(), original.additive(), original.showFrustum());
        }
        super.onClose();
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
        int left = width / 2 - 200;
        graphics.drawString(font, "Changes preview live — walk the beam while you trim.",
                left, height - 28, 0xFF9AA4AC, false);
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

    private static int closestAspect(float value) {
        int best = 0;
        for (int i = 1; i < ASPECTS.length; i++) {
            if (Math.abs(ASPECTS[i] - value) < Math.abs(ASPECTS[best] - value)) {
                best = i;
            }
        }
        return best;
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
            super(x, y, w, 18, Component.empty(),
                    (Math.max(min, Math.min(max, initial)) - min) / (max - min));
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
