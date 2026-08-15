package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.item.ShoulderCameraItem;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateShoulderRigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Aim controls for the worn shoulder rig: pan and tilt relative to the operator's body, plus
 * the lens angle.
 *
 * Aim is relative to the body rather than absolute, because that is how a shoulder mount
 * behaves — turning your shoulders swings the shot, and these offsets aim the camera on top of
 * that. Sliders apply live so the framing can be judged against the feed while dragging, and
 * each change is sent to the server, which owns the value.
 */
public class ShoulderRigScreen extends Screen {

    private final ItemStack rig;

    private float pan;
    private float tilt;
    private float fov;

    public ShoulderRigScreen(ItemStack rig) {
        super(Component.translatable("gui.ndidisplays.shoulder.title"));
        this.rig = rig;
        this.pan = ShoulderCameraItem.pan(rig);
        this.tilt = ShoulderCameraItem.tilt(rig);
        this.fov = ShoulderCameraItem.fov(rig);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 100;
        int y = height / 2 - 46;

        addRenderableWidget(new FloatSlider(left, y, 200, pan,
                -ShoulderCameraItem.MAX_PAN, ShoulderCameraItem.MAX_PAN,
                v -> {
                    pan = (float) v;
                    send();
                },
                v -> String.format("Pan: %+.0f°", v)));
        y += 24;

        addRenderableWidget(new FloatSlider(left, y, 200, tilt,
                -ShoulderCameraItem.MAX_TILT, ShoulderCameraItem.MAX_TILT,
                v -> {
                    tilt = (float) v;
                    send();
                },
                v -> String.format("Tilt: %+.0f°", v)));
        y += 24;

        addRenderableWidget(new FloatSlider(left, y, 200, fov,
                ShoulderCameraItem.MIN_FOV, ShoulderCameraItem.MAX_FOV,
                v -> {
                    fov = (float) v;
                    send();
                },
                // Shown as a lens angle rather than a focal length: the number the shot
                // actually depends on, and what the block cameras already expose.
                v -> String.format("Lens: %.0f° %s", v, v < 35 ? "(tele)" : v > 80 ? "(wide)" : "")));
        y += 30;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.ndidisplays.shoulder.centre"), b -> {
                            pan = 0.0F;
                            tilt = 0.0F;
                            fov = ShoulderCameraItem.DEFAULT_FOV;
                            send();
                            rebuildWidgets();
                        })
                .bounds(left, y, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 102, y, 98, 20).build());
    }

    /** Server owns the value; it re-clamps and stores it on the stack. */
    private void send() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateShoulderRigPacket(pan, tilt, fov));
        // Apply locally too so the feed and the rendered lens respond while dragging, rather
        // than waiting on the round trip.
        ShoulderCameraItem.setAim(rig, pan, tilt, fov);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 66, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("gui.ndidisplays.shoulder.hint"),
                width / 2, height / 2 + 20, 0x9A9A9A);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** The rig is aimed while worn, so the world must stay live behind the screen. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private interface DoubleOut {
        void accept(double v);
    }

    private interface LabelFmt {
        String format(double v);
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
            setMessage(Component.literal(fmt.format(actual())));
        }

        @Override
        protected void applyValue() {
            out.accept(actual());
        }
    }
}
