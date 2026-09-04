package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import dev.nano.ndidisplays.hoist.HoistConfig;
import dev.nano.ndidisplays.hoist.HoistGroups;
import dev.nano.ndidisplays.hoist.HoistStatus;
import dev.nano.ndidisplays.hoist.ScanResult;
import dev.nano.ndidisplays.net.ChainHoistCommandPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The hoist pendant.
 *
 * Laid out the way a motor controller is: the direction keys and STOP are the biggest
 * thing on the screen and always in the same place, the status lamp is next to them, and
 * everything that needs typing — limits, speed, a target height — sits below where it
 * cannot be hit by accident. Group commands are separated from the single-motor ones,
 * because "up" and "up, all four of them" are not the same button anywhere in a venue.
 */
public class ChainHoistScreen extends Screen {

    /**
     * Row origins, measured from the top of the pendant panel rather than the top of the
     * window, so init() and render() agree and the whole thing stays on screen at any GUI
     * scale. Everything is offset by {@link #top()}.
     */
    private static final int ROW_TITLE = 8;
    private static final int ROW_LAMP = 24;
    private static final int ROW_READOUT = 54;
    private static final int ROW_KEYS = 86;
    private static final int ROW_TARGET = 128;
    private static final int ROW_LIMITS = 162;
    private static final int ROW_GROUP = 196;
    private static final int ROW_GROUP_KEYS = 220;
    /** Bottom of the last row plus the hint line. */
    private static final int PANEL_HEIGHT = 278;
    /** Labels sit ten pixels above the row they name. */
    private static final int LABEL_OFFSET = 10;

    private final ChainHoistBlockEntity hoist;

    // Kept on the screen rather than read back from the block entity every frame, so a
    // half-typed limit is not overwritten by a sync packet mid-edit.
    private String minText;
    private String maxText;
    private String speedText;
    private String targetText;
    private String groupText;

    public ChainHoistScreen(ChainHoistBlockEntity hoist) {
        super(Component.translatable("gui.ndidisplays.hoist.title"));
        this.hoist = hoist;
        this.minText = format(hoist.getMinChain());
        this.maxText = format(hoist.getMaxChain());
        this.speedText = format(hoist.getSpeed());
        this.targetText = format(hoist.getTargetChain());
        this.groupText = hoist.getGroup();
    }

    /** Top of the pendant panel, centred vertically but never off the top of the window. */
    private int top() {
        return Math.max(0, (height - PANEL_HEIGHT) / 2);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 110;
        int top = top();
        int y = top + ROW_KEYS;

        // --- Direction keys -------------------------------------------------
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.up"),
                b -> send(ChainHoistCommandPacket.of(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.UP)))
                .bounds(left, y, 68, 26).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.stop"),
                b -> send(ChainHoistCommandPacket.of(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.STOP)))
                .bounds(left + 74, y, 68, 26).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.down"),
                b -> send(ChainHoistCommandPacket.of(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.DOWN)))
                .bounds(left + 148, y, 68, 26).build());
        y = top + ROW_TARGET;

        // --- Target -------------------------------------------------------
        EditBox target = new EditBox(font, left, y, 100, 18,
                Component.translatable("gui.ndidisplays.hoist.target"));
        target.setValue(targetText);
        target.setResponder(v -> targetText = v);
        addRenderableWidget(target);
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.goto"),
                b -> send(ChainHoistCommandPacket.goTo(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.GOTO,
                        parse(targetText, hoist.getTargetChain()))))
                .bounds(left + 106, y, 50, 18).build());
        addRenderableWidget(Button.builder(
                hoist.isAttached()
                        ? Component.translatable("gui.ndidisplays.hoist.detach")
                        : Component.translatable("gui.ndidisplays.hoist.attach"),
                b -> {
                    send(ChainHoistCommandPacket.of(hoist.getBlockPos(), hoist.isAttached()
                            ? ChainHoistCommandPacket.Action.DETACH
                            : ChainHoistCommandPacket.Action.ATTACH));
                    // The button's own label flips with the rig state, so rebuild it once
                    // the server has answered.
                    minecraft.execute(this::rebuildWidgets);
                })
                .bounds(left + 162, y, 54, 18).build());
        y = top + ROW_LIMITS;

        // --- Limits and speed ------------------------------------------------
        addNumBox(left, y, 66, minText, v -> minText = v);
        addNumBox(left + 72, y, 66, maxText, v -> maxText = v);
        addNumBox(left + 144, y, 72, speedText, v -> speedText = v);
        y = top + ROW_GROUP;

        // --- Group ----------------------------------------------------------
        EditBox groupBox = new EditBox(font, left, y, 138, 18,
                Component.translatable("gui.ndidisplays.hoist.group"));
        groupBox.setMaxLength(HoistGroups.MAX_NAME_LENGTH);
        groupBox.setValue(groupText);
        groupBox.setResponder(v -> groupText = v);
        addRenderableWidget(groupBox);
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.apply"), b -> applyConfig())
                .bounds(left + 144, y, 72, 18).build());
        y = top + ROW_GROUP_KEYS;

        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.group_up"),
                b -> send(ChainHoistCommandPacket.of(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.GROUP_UP)))
                .bounds(left, y, 68, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.group_stop"),
                b -> send(ChainHoistCommandPacket.of(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.GROUP_STOP)))
                .bounds(left + 74, y, 68, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.ndidisplays.hoist.group_down"),
                b -> send(ChainHoistCommandPacket.of(hoist.getBlockPos(),
                        ChainHoistCommandPacket.Action.GROUP_DOWN)))
                .bounds(left + 148, y, 68, 20).build());
    }

    private void addNumBox(int x, int y, int w, String initial, java.util.function.Consumer<String> out) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
        box.setValue(initial);
        box.setResponder(out);
        addRenderableWidget(box);
    }

    private void applyConfig() {
        send(ChainHoistCommandPacket.configure(hoist.getBlockPos(),
                parse(minText, hoist.getMinChain()),
                parse(maxText, hoist.getMaxChain()),
                parse(speedText, hoist.getSpeed()),
                groupText));
    }

    private void send(ChainHoistCommandPacket packet) {
        NetworkHandler.CHANNEL.sendToServer(packet);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = width / 2 - 110;
        int top = top();
        graphics.drawCenteredString(font, title, width / 2, top + ROW_TITLE, 0xFFFFFF);

        // --- Status lamp -----------------------------------------------------
        HoistStatus status = hoist.getStatus();
        graphics.fill(left, top + ROW_LAMP, left + 8, top + ROW_LAMP + 16, status.colour());
        graphics.drawString(font, Component.translatable(status.translationKey()),
                left + 14, top + ROW_LAMP + 4, status.colour());

        ScanResult.Failure failure = hoist.getLastFailure();
        if (failure != ScanResult.Failure.NONE
                && (status == HoistStatus.FAULT || failure == ScanResult.Failure.TILTED)) {
            graphics.drawString(font, Component.translatable(failure.translationKey()),
                    left + 14, top + ROW_LAMP + 16, 0xFFE28A2E);
        }

        // --- Readouts --------------------------------------------------------
        readout(graphics, left, top + ROW_READOUT, "gui.ndidisplays.hoist.chain",
                format(hoist.getChainLength()) + " m");
        readout(graphics, left + 110, top + ROW_READOUT, "gui.ndidisplays.hoist.hook_y",
                String.format(Locale.ROOT, "%.2f", hoist.hookY()));
        String load = hoist.isAttached()
                ? Component.translatable("gui.ndidisplays.hoist.load_info",
                        hoist.getLoadBlocks(), hoist.getLoadMotors()).getString()
                  + " · "
                  + Component.translatable(hoist.isOwner()
                          ? "gui.ndidisplays.hoist.role_owner"
                          : "gui.ndidisplays.hoist.role_follower").getString()
                : Component.translatable("gui.ndidisplays.hoist.no_load").getString();
        readout(graphics, left, top + ROW_READOUT + 12, "gui.ndidisplays.hoist.load", load);
        if (hoist.isAttached() && hoist.getRigTilt() > 2.5F) {
            readout(graphics, left + 110, top + ROW_READOUT + 12, "gui.ndidisplays.hoist.tilt",
                    String.format(Locale.ROOT, "%.1f°", hoist.getRigTilt()));
        }

        // --- Field labels ----------------------------------------------------
        graphics.drawString(font, Component.translatable("gui.ndidisplays.hoist.target"),
                left, top + ROW_TARGET - LABEL_OFFSET, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("gui.ndidisplays.hoist.upper_limit_label"),
                left, top + ROW_LIMITS - LABEL_OFFSET, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("gui.ndidisplays.hoist.lower_limit_label"),
                left + 72, top + ROW_LIMITS - LABEL_OFFSET, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("gui.ndidisplays.hoist.speed_label",
                        format(HoistConfig.maxSpeed())),
                left + 144, top + ROW_LIMITS - LABEL_OFFSET, 0xA0A0A0);
        graphics.drawString(font, Component.translatable("gui.ndidisplays.hoist.group"),
                left, top + ROW_GROUP - LABEL_OFFSET, 0xA0A0A0);
        graphics.drawWordWrap(font, Component.translatable("gui.ndidisplays.hoist.hint"),
                left, top + ROW_GROUP_KEYS + 26, 220, 0xFF808A90);
    }

    private void readout(GuiGraphics graphics, int x, int y, String labelKey, String value) {
        Component label = Component.translatable(labelKey);
        graphics.drawString(font, label, x, y, 0xFF808A90, false);
        graphics.drawString(font, value, x + font.width(label) + 6, y, 0xFFD8DEE4, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static float parse(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
