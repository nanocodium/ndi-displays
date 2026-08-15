package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.CameraKind;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateCameraConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/** Configuration screen for all camera rigs (broadcast / PTZ / jib / track dolly). */
public class CameraConfigScreen extends Screen {

    private static final Component[] RES_NAMES = {
            Component.literal("540p"), Component.literal("720p"), Component.literal("1080p")};
    private static final int[] FPS_PRESETS = {24, 30, 60};

    private final NdiCameraBlockEntity camera;

    private EditBox sourceBox;
    private boolean active;
    private int resolution;
    private int fps;
    private float fov;
    private float pan;
    private float tilt;
    private float aux1;
    private float aux2;
    private float aux3;

    public CameraConfigScreen(NdiCameraBlockEntity camera) {
        // Locale.ROOT: a Turkish locale lowercases "JIB" to "jıb" (dotless i), which would
        // miss the translation key and show the raw key as the window title.
        super(Component.translatable("gui.ndidisplays.camera.title."
                + camera.getKind().name().toLowerCase(java.util.Locale.ROOT)));
        this.camera = camera;
        this.active = camera.isActive();
        this.resolution = camera.getResolutionIndex();
        // Snap so the button label matches what Apply actually sends.
        this.fps = closestFps(camera.getFps());
        this.fov = camera.getFov();
        this.pan = camera.getPan();
        this.tilt = camera.getTilt();
        switch (camera.getKind()) {
            case PTZ -> aux1 = camera.getPtzSpeed();
            case JIB -> {
                aux1 = camera.getJibArmLength();
                aux2 = camera.getJibSweep();
                aux3 = camera.getJibPeriod();
            }
            case TRACK -> aux1 = camera.getTrackSpeed();
            default -> {
            }
        }
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 130;
        int y = 36;

        sourceBox = new EditBox(font, left, y, 260, 20, Component.translatable("gui.ndidisplays.camera.source"));
        sourceBox.setMaxLength(128);
        sourceBox.setValue(camera.getSourceName());
        sourceBox.setHint(Component.literal(camera.getEffectiveSourceName()));
        addRenderableWidget(sourceBox);
        y += 26;

        addRenderableWidget(CycleButton.onOffBuilder(active)
                .create(left, y, 128, 20, Component.translatable("gui.ndidisplays.camera.live"),
                        (btn, val) -> active = val));
        addRenderableWidget(CycleButton.<Integer>builder(idx -> RES_NAMES[idx])
                .withValues(0, 1, 2)
                .withInitialValue(resolution)
                .create(left + 132, y, 128, 20, Component.translatable("gui.ndidisplays.camera.resolution"),
                        (btn, val) -> resolution = val));
        y += 24;

        addRenderableWidget(CycleButton.<Integer>builder(f -> Component.literal(f + " fps"))
                .withValues(24, 30, 60)
                .withInitialValue(fps)
                .displayOnlyValue()
                .create(left, y, 128, 20, Component.translatable("gui.ndidisplays.camera.fps"),
                        (btn, val) -> fps = val));
        addRenderableWidget(slider(left + 132, y, 128, fov, 15, 100,
                v -> fov = (float) v, v -> String.format("Zoom (FOV): %.0f°", v)));
        y += 24;

        addRenderableWidget(slider(left, y, 128, pan, -180, 180,
                v -> pan = (float) v, v -> String.format("Pan: %+.0f°", v)));
        addRenderableWidget(slider(left + 132, y, 128, tilt, -85, 85,
                v -> tilt = (float) v, v -> String.format("Tilt: %+.0f°", v)));
        y += 24;

        switch (camera.getKind()) {
            case PTZ -> {
                addRenderableWidget(slider(left, y, 260, aux1, 5, 180,
                        v -> aux1 = (float) v, v -> String.format("Slew speed: %.0f°/s", v)));
                y += 24;
            }
            case JIB -> {
                // Upper bound comes from the block entity rather than a literal, so the slider
                // can never again disagree with what applyConfig will accept — that mismatch is
                // what kept the arm pinned at 8m after the limit was raised.
                addRenderableWidget(slider(left, y, 128, aux1, 2,
                        dev.nano.ndidisplays.block.NdiCameraBlockEntity.MAX_JIB_ARM,
                        v -> aux1 = (float) v, v -> String.format("Arm length: %.1f m", v)));
                addRenderableWidget(slider(left + 132, y, 128, aux2, 10, 170,
                        v -> aux2 = (float) v, v -> String.format("Sweep: %.0f°", v)));
                y += 24;
                addRenderableWidget(slider(left, y, 260, aux3, 4, 40,
                        v -> aux3 = (float) v, v -> String.format("Sweep period: %.0f s", v)));
                y += 24;
            }
            case TRACK -> {
                addRenderableWidget(slider(left, y, 260, aux1, 0.1, 4,
                        v -> aux1 = (float) v, v -> String.format("Dolly speed: %.2f m/s", v)));
                y += 24;
            }
            default -> {
            }
        }
        y += 6;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"), b -> apply())
                .bounds(cx - 130, y, 128, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 128, 20).build());
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateCameraConfigPacket(
                camera.getBlockPos(), sourceBox.getValue(), active, resolution, fps, fov,
                pan, tilt, aux1, aux2, aux3));
        onClose();
    }

    private static Integer closestFps(int fps) {
        int best = FPS_PRESETS[0];
        for (int p : FPS_PRESETS) {
            if (Math.abs(p - fps) < Math.abs(best - fps)) {
                best = p;
            }
        }
        return best;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        String status = NdiManager.isAvailable()
                ? "NDI output: " + camera.getEffectiveSourceName()
                : NdiManager.getStatus();
        graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 28,
                NdiManager.isAvailable() ? 0x55FF55 : 0xFF5555);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private interface LabelFormatter {
        String label(double value);
    }

    private static AbstractSliderButton slider(int x, int y, int w, double initial, double min, double max,
                                               DoubleConsumer out, LabelFormatter fmt) {
        return new AbstractSliderButton(x, y, w, 20, Component.empty(),
                (Math.max(min, Math.min(max, initial)) - min) / (max - min)) {
            {
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
        };
    }
}
