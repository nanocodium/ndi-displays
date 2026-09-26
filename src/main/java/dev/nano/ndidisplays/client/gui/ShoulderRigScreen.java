package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.client.ShoulderOperatorMode;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.item.ShoulderCameraItem;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateShoulderRigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.DoubleConsumer;

/**
 * Configuration screen for the worn shoulder rig — the same layout as
 * {@link CameraConfigScreen} for the block cameras (source, live, resolution, frame rate, zoom,
 * take control), minus pan and tilt: the rig follows where the operator looks, so there is
 * nothing to aim.
 *
 * Zoom applies live so the framing can be judged against the feed while dragging; everything
 * else is sent on Apply. The server owns the values and stores them on the stack.
 */
public class ShoulderRigScreen extends Screen {

    private static final Component[] RES_NAMES = {
            Component.literal("540p"), Component.literal("720p"), Component.literal("1080p")};
    private static final int[] FPS_PRESETS = {24, 30, 60};

    private final ItemStack rig;
    private final float originalFov;

    private EditBox sourceBox;
    private boolean live;
    private int resolution;
    private int fps;
    private float fov;
    private boolean applied;

    public ShoulderRigScreen(ItemStack rig) {
        super(Component.translatable("gui.ndidisplays.shoulder.title"));
        this.rig = rig;
        this.live = ShoulderCameraItem.live(rig);
        this.resolution = ShoulderCameraItem.resolutionIndex(rig);
        // Snap so the button label matches what Apply actually sends.
        this.fps = closestFps(ShoulderCameraItem.fps(rig));
        this.fov = ShoulderCameraItem.fov(rig);
        this.originalFov = fov;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 130;
        int y = 36;

        sourceBox = new EditBox(font, left, y, 260, 20,
                Component.translatable("gui.ndidisplays.camera.source"));
        sourceBox.setMaxLength(ShoulderCameraItem.MAX_SOURCE_LENGTH);
        sourceBox.setValue(ShoulderCameraItem.source(rig));
        sourceBox.setHint(Component.literal(defaultSourceName()));
        addRenderableWidget(sourceBox);
        y += 26;

        addRenderableWidget(CycleButton.onOffBuilder(live)
                .create(left, y, 128, 20, Component.translatable("gui.ndidisplays.camera.live"),
                        (btn, val) -> live = val));
        addRenderableWidget(CycleButton.<Integer>builder(idx -> RES_NAMES[idx])
                .withValues(0, 1, 2)
                .withInitialValue(resolution)
                .create(left + 132, y, 128, 20,
                        Component.translatable("gui.ndidisplays.camera.resolution"),
                        (btn, val) -> resolution = val));
        y += 24;

        addRenderableWidget(CycleButton.<Integer>builder(f -> Component.literal(f + " fps"))
                .withValues(24, 30, 60)
                .withInitialValue(fps)
                .displayOnlyValue()
                .create(left, y, 128, 20, Component.translatable("gui.ndidisplays.camera.fps"),
                        (btn, val) -> fps = val));
        addRenderableWidget(slider(left + 132, y, 128, fov,
                ShoulderCameraItem.MIN_FOV, ShoulderCameraItem.MAX_FOV,
                v -> {
                    fov = (float) v;
                    // Local preview only: the client captures its own feed from this stack,
                    // so the picture responds while dragging. The server copy lands on Apply.
                    ShoulderCameraItem.setAim(rig, 0.0F, 0.0F, fov);
                },
                v -> String.format("Zoom (FOV): %.0f°", v)));
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Take control"), b -> {
            apply();
            if (!ShoulderOperatorMode.setActive(true) && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("gui.ndidisplays.shoulder.not_worn"), true);
            }
        }).bounds(left, y, 260, 20).build());
        y += 26;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"),
                        b -> apply())
                .bounds(cx - 130, y, 128, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 128, 20).build());
    }

    private String defaultSourceName() {
        return "MC Shoulder " + (minecraft.player == null ? ""
                : minecraft.player.getGameProfile().getName());
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateShoulderRigPacket(
                sourceBox.getValue(), live, resolution, fps, fov));
        // Apply locally too, so the feed picks the settings up now rather than after the
        // stack round-trips through the server.
        ShoulderCameraItem.setConfig(rig, sourceBox.getValue(), live, resolution, fps, fov);
        applied = true;
        onClose();
    }

    /** Cancel undoes the live zoom preview; the server never saw it. */
    @Override
    public void removed() {
        if (!applied) {
            ShoulderCameraItem.setAim(rig, ShoulderCameraItem.pan(rig),
                    ShoulderCameraItem.tilt(rig), originalFov);
        }
        super.removed();
    }

    private static int closestFps(int fps) {
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
        String custom = sourceBox.getValue().trim();
        String status = NdiManager.isAvailable()
                ? "NDI output: " + (custom.isEmpty() ? defaultSourceName() : custom)
                : NdiManager.getStatus();
        graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 28,
                NdiManager.isAvailable() ? 0x55FF55 : 0xFF5555);
    }

    /** The rig is aimed while worn, so the world must stay live behind the screen. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private interface LabelFormatter {
        String label(double value);
    }

    private static AbstractSliderButton slider(int x, int y, int w, double initial, double min,
                                               double max, DoubleConsumer out, LabelFormatter fmt) {
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
