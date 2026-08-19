package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.compat.xaero.XaeroCompat;
import dev.nano.ndidisplays.entity.DroneEntity;
import dev.nano.ndidisplays.net.DroneActionPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateDroneConfigPacket;
import dev.nano.ndidisplays.path.DronePath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/** NDI settings plus the native waypoint list for one drone. */
public class DroneConfigScreen extends Screen {

    private static final Component[] RES_NAMES = {
            Component.literal("540p"), Component.literal("720p"), Component.literal("1080p")};
    private static final int[] FPS_PRESETS = {24, 30, 60};

    private static DroneEntity markersFor;

    private final DroneEntity drone;
    private EditBox sourceBox;
    private boolean live;
    private int resolution;
    private int fps;
    private float fov;
    private float maxSpeed;
    private int pathRevision = -1;
    private final List<Button> pathButtons = new ArrayList<>();

    public DroneConfigScreen(DroneEntity drone) {
        super(Component.translatable("gui.ndidisplays.drone.title"));
        this.drone = drone;
        this.live = drone.isLive();
        this.resolution = drone.getResolutionIndex();
        this.fps = closestFps(drone.getFps());
        this.fov = drone.getFov();
        this.maxSpeed = drone.getMaxSpeed();
    }

    public static DroneEntity markersDrone() {
        return markersFor;
    }

    @Override
    protected void init() {
        markersFor = drone;
        int cx = width / 2;
        int left = cx - 160;
        int y = 28;

        sourceBox = new EditBox(font, left, y, 320, 20,
                Component.translatable("gui.ndidisplays.camera.source"));
        sourceBox.setMaxLength(DroneEntity.MAX_SOURCE_NAME);
        sourceBox.setValue(drone.getSourceName());
        sourceBox.setHint(Component.literal(drone.getEffectiveSourceName()));
        addRenderableWidget(sourceBox);
        y += 24;

        addRenderableWidget(CycleButton.onOffBuilder(live)
                .create(left, y, 104, 20, Component.translatable("gui.ndidisplays.camera.live"),
                        (btn, val) -> live = val));
        addRenderableWidget(CycleButton.<Integer>builder(idx -> RES_NAMES[idx])
                .withValues(0, 1, 2)
                .withInitialValue(resolution)
                .create(left + 108, y, 104, 20,
                        Component.translatable("gui.ndidisplays.camera.resolution"),
                        (btn, val) -> resolution = val));
        addRenderableWidget(CycleButton.<Integer>builder(f -> Component.literal(f + " fps"))
                .withValues(24, 30, 60)
                .withInitialValue(fps)
                .displayOnlyValue()
                .create(left + 216, y, 104, 20,
                        Component.translatable("gui.ndidisplays.camera.fps"),
                        (btn, val) -> fps = val));
        y += 24;

        addRenderableWidget(slider(left, y, 156, fov, 15, 110,
                v -> fov = (float) v, v -> String.format("FOV: %.0f°", v)));
        addRenderableWidget(slider(left + 164, y, 156, maxSpeed, 1, 16,
                v -> maxSpeed = (float) v, v -> String.format("Speed: %.1f m/s", v)));
        y += 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.drone.add_here"),
                b -> send(DroneActionPacket.Action.ADD_HERE, 0))
                .bounds(left, y, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.drone.play"),
                b -> send(DroneActionPacket.Action.PLAY, 0))
                .bounds(left + 80, y, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.drone.stop"),
                b -> send(DroneActionPacket.Action.STOP, 0))
                .bounds(left + 140, y, 56, 20).build());
        addRenderableWidget(Button.builder(modeLabel(),
                b -> send(DroneActionPacket.Action.CYCLE_MODE, 0))
                .bounds(left + 200, y, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.drone.clear"),
                b -> send(DroneActionPacket.Action.CLEAR, 0))
                .bounds(left + 276, y, 44, 20).build());
        y += 24;

        if (XaeroCompat.available()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.drone.import_xaero"),
                    b -> importXaero())
                    .bounds(left, y, 320, 20).build());
            y += 24;
        }

        rebuildPathRows(left, y);

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.pad.open"),
                b -> minecraft.setScreen(new DronePadOptionsScreen(this)))
                .bounds(left, height - 52, 320, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"), b -> apply())
                .bounds(cx - 130, height - 28, 128, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, height - 28, 128, 20).build());
    }

    private void rebuildPathRows(int left, int y) {
        for (Button button : pathButtons) {
            removeWidget(button);
        }
        pathButtons.clear();
        DronePath path = drone.path();
        pathRevision = path.size() * 31 + path.mode().ordinal();
        int shown = Math.min(path.size(), 8);
        for (int i = 0; i < shown; i++) {
            DronePath.Waypoint wp = path.points().get(i);
            int rowY = y + i * 18;
            int idx = i;
            String label = String.format("#%d  %.0f %.0f %.0f", i + 1, wp.pos.x, wp.pos.y, wp.pos.z);
            Button row = Button.builder(Component.literal(label), b -> {})
                    .bounds(left, rowY, 188, 16).build();
            row.active = false;
            Button up = Button.builder(Component.literal("▲"),
                    b -> send(DroneActionPacket.Action.MOVE_UP, idx))
                    .bounds(left + 192, rowY, 18, 16).build();
            Button down = Button.builder(Component.literal("▼"),
                    b -> send(DroneActionPacket.Action.MOVE_DOWN, idx))
                    .bounds(left + 212, rowY, 18, 16).build();
            Button del = Button.builder(Component.literal("✕"),
                    b -> send(DroneActionPacket.Action.REMOVE, idx))
                    .bounds(left + 232, rowY, 18, 16).build();
            pathButtons.add(addRenderableWidget(row));
            pathButtons.add(addRenderableWidget(up));
            pathButtons.add(addRenderableWidget(down));
            pathButtons.add(addRenderableWidget(del));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!drone.isAlive()) {
            onClose();
            return;
        }
        int rev = drone.path().size() * 31 + drone.path().mode().ordinal();
        if (rev != pathRevision) {
            rebuildWidgets();
        }
    }

    private void send(DroneActionPacket.Action action, int index) {
        NetworkHandler.CHANNEL.sendToServer(new DroneActionPacket(drone.getUUID(), action, index));
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateDroneConfigPacket(
                drone.getUUID(), sourceBox.getValue(), live, resolution, fps, fov, maxSpeed));
        onClose();
    }

    private void importXaero() {
        List<Vec3> imported = XaeroCompat.currentWorldWaypoints();
        if (imported.isEmpty()) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("gui.ndidisplays.drone.xaero_empty"), true);
            }
            return;
        }
        XaeroCompat.importInto(drone, imported);
    }

    private Component modeLabel() {
        return Component.translatable("gui.ndidisplays.drone.mode."
                + drone.path().mode().name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        String status = NdiManager.isAvailable()
                ? "NDI: " + drone.getEffectiveSourceName()
                : NdiManager.getStatus();
        graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 42,
                NdiManager.isAvailable() ? 0x55FF55 : 0xFF5555);
        graphics.drawString(font, Component.translatable("gui.ndidisplays.drone.path_hint",
                drone.path().size()), width / 2 - 160, height - 54, 0xA0A0A0, false);
    }

    @Override
    public void removed() {
        if (markersFor == drone) {
            markersFor = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
