package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.ClientConfig;
import dev.nano.ndidisplays.client.DroneGamepad;
import dev.nano.ndidisplays.client.StickBinding;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;

/**
 * Walks the player through four stick pushes so we can record the real raw
 * GLFW axes — left/right labels are meaningless on D-input and many generic pads.
 */
public class DroneStickCalibrateScreen extends Screen {

    private enum Step {
        REST,
        MOVE_FWD,
        MOVE_RIGHT,
        LOOK_RIGHT,
        LOOK_UP
    }

    private static final float THRESHOLD = 0.52F;
    private static final int HOLD_TICKS = 8;

    private final Screen parent;
    private Step step = Step.REST;
    private int restTicks = 24;
    @Nullable
    private float[] rest;
    private final boolean[] used = new boolean[32];
    private int pendingAxis = -1;
    private float pendingDelta;
    private int hold;

    private int moveX = 0;
    private int moveY = 1;
    private int lookX = 2;
    private int lookY = 3;
    private boolean invMoveX;
    private boolean invMoveY;
    private boolean invLookX;
    private boolean invLookY;

    public DroneStickCalibrateScreen(Screen parent) {
        super(Component.translatable("gui.ndidisplays.pad.calibrate_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
        float[] axes = DroneGamepad.rawAxes();
        if (axes == null || axes.length < 2) {
            rest = null;
            restTicks = 24;
            step = Step.REST;
            return;
        }
        if (step == Step.REST) {
            if (--restTicks <= 0) {
                rest = axes.clone();
                step = Step.MOVE_FWD;
                resetHold();
            }
            return;
        }
        if (rest == null || rest.length != axes.length) {
            rest = axes.clone();
            resetHold();
            return;
        }

        int best = -1;
        float bestDelta = 0.0F;
        int limit = Math.min(axes.length, used.length);
        for (int i = 0; i < limit; i++) {
            if (used[i]) {
                continue;
            }
            // Triggers rest at ±1; ignore them so a pulled LT does not become a stick.
            if (rest[i] < -0.70F || rest[i] > 0.70F) {
                continue;
            }
            float delta = axes[i] - rest[i];
            if (Math.abs(delta) > Math.abs(bestDelta)) {
                best = i;
                bestDelta = delta;
            }
        }
        if (best >= 0 && Math.abs(bestDelta) >= THRESHOLD) {
            if (best == pendingAxis && Math.signum(bestDelta) == Math.signum(pendingDelta)) {
                hold++;
            } else {
                pendingAxis = best;
                pendingDelta = bestDelta;
                hold = 1;
            }
            if (hold >= HOLD_TICKS) {
                accept(pendingAxis, pendingDelta);
            }
        } else {
            resetHold();
        }
    }

    private void accept(int axis, float delta) {
        if (axis >= 0 && axis < used.length) {
            used[axis] = true;
        }
        switch (step) {
            case MOVE_FWD -> {
                moveY = axis;
                invMoveY = delta > 0.0F;
                step = Step.MOVE_RIGHT;
            }
            case MOVE_RIGHT -> {
                moveX = axis;
                invMoveX = delta < 0.0F;
                step = Step.LOOK_RIGHT;
            }
            case LOOK_RIGHT -> {
                lookX = axis;
                invLookX = delta < 0.0F;
                step = Step.LOOK_UP;
            }
            case LOOK_UP -> {
                lookY = axis;
                invLookY = delta > 0.0F;
                saveAndClose();
                return;
            }
            default -> {
            }
        }
        resetHold();
    }

    private void saveAndClose() {
        ClientConfig.DRONE_PAD_MOVE_STICK.set(new StickBinding(moveX, moveY, invMoveX, invMoveY).serialize());
        ClientConfig.DRONE_PAD_LOOK_STICK.set(new StickBinding(lookX, lookY, invLookX, invLookY).serialize());
        DroneGamepad.rememberActivePad();
        ClientConfig.SPEC.save();
        onClose();
    }

    private void resetHold() {
        pendingAxis = -1;
        pendingDelta = 0.0F;
        hold = 0;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);

        String pad = DroneGamepad.activePadName();
        graphics.drawCenteredString(font,
                pad == null
                        ? Component.translatable("gui.ndidisplays.pad.no_pad")
                        : Component.literal(pad),
                width / 2, 32, pad == null ? 0xFF5555 : 0xA0A0A0);

        graphics.drawCenteredString(font, instruction(), width / 2, 52, 0xFFFFA0);
        if (step != Step.REST && hold > 0) {
            int pct = Mth.clamp(hold * 100 / HOLD_TICKS, 0, 100);
            graphics.drawCenteredString(font,
                    Component.translatable("gui.ndidisplays.pad.calibrate_hold", pct),
                    width / 2, 66, 0x55FF55);
        }

        float[] axes = DroneGamepad.rawAxes();
        if (axes != null) {
            drawAxisMeters(graphics, axes);
        }
    }

    private Component instruction() {
        return switch (step) {
            case REST -> Component.translatable("gui.ndidisplays.pad.calibrate_rest");
            case MOVE_FWD -> Component.translatable("gui.ndidisplays.pad.calibrate_move_fwd");
            case MOVE_RIGHT -> Component.translatable("gui.ndidisplays.pad.calibrate_move_right");
            case LOOK_RIGHT -> Component.translatable("gui.ndidisplays.pad.calibrate_look_right");
            case LOOK_UP -> Component.translatable("gui.ndidisplays.pad.calibrate_look_up");
        };
    }

    private void drawAxisMeters(GuiGraphics graphics, float[] axes) {
        int shown = Math.min(axes.length, 8);
        int meterW = 180;
        int left = width / 2 - meterW / 2;
        int top = 88;
        for (int i = 0; i < shown; i++) {
            int y = top + i * 16;
            float restValue = rest != null && i < rest.length ? rest[i] : 0.0F;
            float delta = axes[i] - restValue;
            boolean taken = i < used.length && used[i];
            int color = taken ? 0xFF555555 : (i == pendingAxis ? 0xFF55FF55 : 0xFF888888);
            graphics.drawString(font, "A" + i, left - 28, y + 1, taken ? 0x666666 : 0xFFFFFF, false);
            graphics.fill(left, y + 5, left + meterW, y + 9, 0xFF202020);
            int mid = left + meterW / 2;
            int bar = (int) (Mth.clamp(delta, -1.0F, 1.0F) * (meterW / 2 - 1));
            if (bar >= 0) {
                graphics.fill(mid, y + 4, mid + Math.max(bar, 0), y + 10, color);
            } else {
                graphics.fill(mid + bar, y + 4, mid, y + 10, color);
            }
            graphics.fill(mid, y + 3, mid + 1, y + 11, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
