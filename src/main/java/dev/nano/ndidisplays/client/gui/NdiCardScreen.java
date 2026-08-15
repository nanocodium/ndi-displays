package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.item.NdiConfigCardItem;
import dev.nano.ndidisplays.net.ApplyNdiCardRegionPacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateNdiCardPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * NDI configuration card: pick a source once, then right-click screens to apply it —
 * the same workflow as Theatrical's DMX configuration card, but for video. When the
 * card holds a WorldEdit-style selection (sneak + right/left click two winches), an
 * extra button applies the source to every screen inside the box in one shot.
 */
public class NdiCardScreen extends Screen {

    private final InteractionHand hand;

    /** Survives widget rebuilds (resize), so typed input is never lost. */
    private String source;
    private EditBox sourceBox;
    private NdiSourcePicker picker;
    private boolean hasSelection;
    /** Winch motor mode the card imposes: keep / linked / twin. */
    private int winchMode;
    /**
     * When on, Apply Region stitches the park into one image. When off, each
     * motor is reset to a full-frame canvas (1×1) so it shows the whole source.
     */
    private boolean autoMapCanvas = false;

    public NdiCardScreen(InteractionHand hand, String storedSource) {
        super(Component.translatable("gui.ndidisplays.card.title"));
        this.hand = hand;
        this.source = storedSource;
        ItemStack card = heldCard();
        this.winchMode = card != null ? NdiConfigCardItem.storedWinchMode(card)
                : NdiConfigCardItem.WINCH_MODE_KEEP;
        this.autoMapCanvas = card != null && NdiConfigCardItem.storedAutoMap(card);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 40;

        sourceBox = new EditBox(font, left, y, 264, 18, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(LedPanelBlockEntity.MAX_SOURCE_NAME);
        sourceBox.setValue(source);
        sourceBox.setResponder(value -> source = value);
        addRenderableWidget(sourceBox);
        y += 24;

        picker = new NdiSourcePicker(4, this::addRenderableWidget, this::removeWidget, name -> {
            source = name;
            sourceBox.setValue(name);
        });
        picker.init(left, y);
        y += picker.height() + 10;

        // Winch motor mode the card imposes when applied to winches (click or region).
        addRenderableWidget(net.minecraft.client.gui.components.CycleButton.<Integer>builder(mode ->
                        Component.translatable(switch (mode) {
                            case NdiConfigCardItem.WINCH_MODE_LINKED -> "gui.ndidisplays.card.mode.linked";
                            case NdiConfigCardItem.WINCH_MODE_TWIN -> "gui.ndidisplays.card.mode.twin";
                            default -> "gui.ndidisplays.card.mode.keep";
                        }))
                .withValues(java.util.List.of(NdiConfigCardItem.WINCH_MODE_KEEP,
                        NdiConfigCardItem.WINCH_MODE_LINKED, NdiConfigCardItem.WINCH_MODE_TWIN))
                .withInitialValue(winchMode)
                .create(left, y, 264, 20, Component.translatable("gui.ndidisplays.card.mode"),
                        (btn, val) -> winchMode = val));
        y += 24;

        addRenderableWidget(net.minecraft.client.gui.components.CycleButton.<Boolean>builder(on ->
                        Component.translatable(on
                                ? "gui.ndidisplays.card.automap.on"
                                : "gui.ndidisplays.card.automap.off"))
                .withValues(java.util.List.of(Boolean.FALSE, Boolean.TRUE))
                .withInitialValue(autoMapCanvas)
                .create(left, y, 264, 20, Component.translatable("gui.ndidisplays.card.automap"),
                        (btn, val) -> autoMapCanvas = val));
        y += 24;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.card.save"), b -> apply())
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
        y += 24;

        ItemStack card = heldCard();
        hasSelection = card != null && NdiConfigCardItem.hasSelection(card);
        if (hasSelection) {
            BlockPos pos1 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS1);
            BlockPos pos2 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS2);
            Component label = Component.translatable("gui.ndidisplays.card.apply_region",
                    Math.abs(pos2.getX() - pos1.getX()) + 1,
                    Math.abs(pos2.getY() - pos1.getY()) + 1,
                    Math.abs(pos2.getZ() - pos1.getZ()) + 1);
            addRenderableWidget(Button.builder(label, b -> applyRegion())
                    .bounds(cx - 132, y, 190, 20).build());
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.ndidisplays.card.clear_selection"),
                            b -> clearSelection())
                    .bounds(cx + 62, y, 70, 20).build());
            y += 24;
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.ndidisplays.card.park_preview"),
                            b -> openParkPreview())
                    .bounds(cx - 132, y, 264, 20).build());
        }
    }

    private ItemStack heldCard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        ItemStack stack = mc.player.getItemInHand(hand);
        return stack.getItem() instanceof NdiConfigCardItem ? stack : null;
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateNdiCardPacket(
                hand == InteractionHand.MAIN_HAND, sourceBox.getValue().trim(), winchMode,
                autoMapCanvas));
        onClose();
    }

    /** Stores the source on the card AND applies it to every screen in the selection. */
    private void applyRegion() {
        NetworkHandler.CHANNEL.sendToServer(new ApplyNdiCardRegionPacket(
                hand == InteractionHand.MAIN_HAND, sourceBox.getValue().trim(), false, winchMode,
                autoMapCanvas));
        onClose();
    }

    private void clearSelection() {
        NetworkHandler.CHANNEL.sendToServer(new ApplyNdiCardRegionPacket(
                hand == InteractionHand.MAIN_HAND, "", true, winchMode, false));
        // Reflect it locally right away; the server does the authoritative clear.
        ItemStack card = heldCard();
        if (card != null) {
            NdiConfigCardItem.clearSelection(card);
        }
        rebuildWidgets();
    }

    private void openParkPreview() {
        ItemStack card = heldCard();
        if (card == null || !NdiConfigCardItem.hasSelection(card)) {
            return;
        }
        BlockPos pos1 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS1);
        BlockPos pos2 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS2);
        Minecraft.getInstance().setScreen(new WinchParkScreen(this, pos1, pos2));
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
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("gui.ndidisplays.card.hint"), width / 2, 26, 0xA0A0A0);
        graphics.drawString(font, NdiManager.getStatus(), left, height - 20,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060);
        if (picker != null) {
            picker.renderScrollbar(graphics);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
