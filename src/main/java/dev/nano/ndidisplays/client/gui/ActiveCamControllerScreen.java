package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.activecam.ActiveCamHeadMode;
import dev.nano.ndidisplays.activecam.ActiveCamMode;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import dev.nano.ndidisplays.client.ActiveCamClientState;
import dev.nano.ndidisplays.client.ActiveCamPilotMode;
import dev.nano.ndidisplays.net.ActiveCamActionPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateActiveCamConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class ActiveCamControllerScreen extends Screen {

    private final ActiveCamControllerBlockEntity ctrl;
    private String source;
    private boolean live;
    private int resolution;
    private int fps;
    private float workingHeight;
    private float speed;
    private float accel;
    private ActiveCamHeadMode head;
    private String targetName = "";
    private EditBox sourceBox;
    private EditBox targetBox;

    public ActiveCamControllerBlockEntity controller() {
        return ctrl;
    }

    public ActiveCamControllerScreen(ActiveCamControllerBlockEntity ctrl) {
        super(Component.translatable("gui.ndidisplays.activecam.title"));
        this.ctrl = ctrl;
        this.source = ctrl.getSourceName();
        this.live = ctrl.isLiveRequested();
        this.resolution = ctrl.getResolution();
        this.fps = ctrl.getFps();
        this.workingHeight = ctrl.getWorkingHeight();
        this.speed = ctrl.getMaxSpeed();
        this.accel = ctrl.getAcceleration();
        this.head = ctrl.liveHead();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 160;
        int y = 28;

        sourceBox = new EditBox(font, left, y, 200, 18,
                Component.translatable("gui.ndidisplays.camera.source"));
        sourceBox.setMaxLength(ActiveCamControllerBlockEntity.MAX_SOURCE_NAME);
        sourceBox.setValue(source);
        sourceBox.setResponder(v -> source = v);
        addRenderableWidget(sourceBox);
        addRenderableWidget(CycleButton.booleanBuilder(
                        Component.translatable("gui.ndidisplays.camera.live"),
                        Component.literal("Off"))
                .withInitialValue(live)
                .create(left + 208, y, 72, 18, Component.empty(), (b, v) -> live = v));
        y += 24;

        addRenderableWidget(new FloatSlider(left, y, 110, workingHeight, 2, 80,
                v -> workingHeight = (float) v, v -> String.format("Height %.1f", v)));
        addRenderableWidget(new FloatSlider(left + 118, y, 110, speed,
                ActiveCamControllerBlockEntity.MIN_SPEED, ActiveCamControllerBlockEntity.MAX_SPEED,
                v -> speed = (float) v, v -> String.format("Speed %.1f", v)));
        addRenderableWidget(new FloatSlider(left + 236, y, 84, accel,
                ActiveCamControllerBlockEntity.MIN_ACCEL, ActiveCamControllerBlockEntity.MAX_ACCEL,
                v -> accel = (float) v, v -> String.format("Accel %.1f", v)));
        y += 24;

        addRenderableWidget(CycleButton.<ActiveCamHeadMode>builder(m -> Component.translatable(
                        m == ActiveCamHeadMode.TRACK_TARGET
                                ? "gui.ndidisplays.activecam.track"
                                : "gui.ndidisplays.activecam.free"))
                .withValues(ActiveCamHeadMode.RECORDED, ActiveCamHeadMode.TRACK_TARGET)
                .withInitialValue(head)
                .create(left, y, 160, 18, Component.translatable("gui.ndidisplays.activecam.camera"),
                        (b, v) -> head = v));
        targetBox = new EditBox(font, left + 168, y, 140, 18,
                Component.translatable("gui.ndidisplays.activecam.target"));
        targetBox.setMaxLength(32);
        targetBox.setValue(targetName);
        targetBox.setResponder(v -> targetName = v);
        addRenderableWidget(targetBox);
        y += 22;
        Button threeD = Button.builder(Component.translatable("gui.ndidisplays.activecam.mode_3d"), b -> {})
                .bounds(left, y, 160, 18).build();
        threeD.active = false;
        addRenderableWidget(threeD);
        y += 28;

        int x = left;
        x = action(x, y, 72, "gui.ndidisplays.activecam.record", ActiveCamActionPacket.Action.RECORD);
        x = action(x, y, 52, "gui.ndidisplays.activecam.play", ActiveCamActionPacket.Action.PLAY);
        x = action(x, y, 56, "gui.ndidisplays.activecam.pause", ActiveCamActionPacket.Action.PAUSE);
        x = action(x, y, 52, "gui.ndidisplays.activecam.stop", ActiveCamActionPacket.Action.STOP);
        x = action(x, y, 52, "gui.ndidisplays.activecam.loop", ActiveCamActionPacket.Action.LOOP);
        action(x, y, 52, "gui.ndidisplays.activecam.clear_path", ActiveCamActionPacket.Action.CLEAR_PATH);
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.activecam.estop"),
                        b -> send(ActiveCamActionPacket.Action.ESTOP))
                .bounds(left, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.activecam.clear_estop"),
                        b -> send(ActiveCamActionPacket.Action.CLEAR_ESTOP))
                .bounds(left + 108, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.activecam.take"),
                        b -> {
                            apply(false);
                            send(ActiveCamActionPacket.Action.TAKE_CONTROL);
                            ActiveCamPilotMode.take(ctrl.getBlockPos());
                            onClose();
                        })
                .bounds(left + 216, y, 100, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"), b -> apply(true))
                .bounds(cx - 50, y, 100, 20).build());
    }

    private int action(int x, int y, int w, String key, ActiveCamActionPacket.Action action) {
        addRenderableWidget(Button.builder(Component.translatable(key), b -> send(action))
                .bounds(x, y, w, 20).build());
        return x + w + 4;
    }

    private void send(ActiveCamActionPacket.Action action) {
        NetworkHandler.CHANNEL.sendToServer(new ActiveCamActionPacket(ctrl.getBlockPos(), action));
    }

    private void apply(boolean close) {
        UUID target = null;
        boolean has = false;
        if (minecraft != null && minecraft.level != null && !targetName.isBlank()) {
            for (var player : minecraft.level.players()) {
                if (player.getGameProfile().getName().equalsIgnoreCase(targetName.trim())) {
                    target = player.getUUID();
                    has = true;
                    break;
                }
            }
        }
        NetworkHandler.CHANNEL.sendToServer(new UpdateActiveCamConfigPacket(
                ctrl.getBlockPos(), sourceBox.getValue(), live, resolution, fps, workingHeight, speed, accel,
                ActiveCamMode.TWO_D.ordinal(), head.ordinal(), has, target));
        if (close) {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        int left = width / 2 - 160;
        int y = height - 40;
        if (ctrl.getLevel() != null) {
            for (int i = 0; i < 4; i++) {
                boolean ok = ctrl.winchConnected(ctrl.getLevel(), i);
                g.drawString(font, Component.translatable(
                                ok ? "gui.ndidisplays.activecam.winch_ok"
                                        : "gui.ndidisplays.activecam.winch_missing", i + 1).getString(),
                        left + i * 80, y, ok ? 0x88FF88 : 0xFF8888, false);
            }
            var pose = ActiveCamClientState.interpolated(ctrl.getBlockPos(), 1.0F);
            double cy = pose != null ? pose.y : ctrl.pose().y;
            g.drawString(font, String.format("Y %.2f → %.1f   %s  %s pts",
                            cy, workingHeight, ctrl.waypoints().mode().name(),
                            ctrl.waypoints().size()),
                    left, y + 12, 0xC4B5FD, false);
        }
        g.drawString(font, Component.translatable("gui.ndidisplays.activecam.mode_2d").getString(),
                left, 14, 0xC4B5FD, false);
        if (ctrl.isEStop()) {
            g.drawCenteredString(font, Component.translatable("gui.ndidisplays.activecam.estop_on"),
                    width / 2, y, 0xFF5555);
        }
        super.render(g, mouseX, mouseY, partial);
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
