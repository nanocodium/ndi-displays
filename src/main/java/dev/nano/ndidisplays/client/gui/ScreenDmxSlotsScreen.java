package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.DmxScreen;
import dev.nano.ndidisplays.block.ScreenDmxState;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateScreenDmxSlotsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The DMX side of a fixed screen: eight NDI source slots the console's
 * source-select channel cuts between (CH2, one slot per 32 DMX values), plus the
 * current patch for reference. Patching itself goes through Theatrical's
 * configuration card, like the fixtures.
 */
public class ScreenDmxSlotsScreen extends Screen {

    private final BlockEntity target;
    private final ScreenDmxState dmx;
    @Nullable
    private final Screen parent;

    private final String[] values = new String[ScreenDmxState.SLOTS];
    private final List<EditBox> boxes = new ArrayList<>();

    public ScreenDmxSlotsScreen(BlockEntity target, @Nullable Screen parent) {
        super(Component.translatable("gui.ndidisplays.screen_dmx.title"));
        this.target = target;
        this.dmx = ((DmxScreen) target).dmx();
        this.parent = parent;
        for (int i = 0; i < ScreenDmxState.SLOTS; i++) {
            values[i] = dmx.getSlot(i);
        }
    }

    @Override
    protected void init() {
        boxes.clear();
        int cx = width / 2;
        int left = cx - 132;
        int y = 44;
        for (int i = 0; i < ScreenDmxState.SLOTS; i++) {
            int col = i % 2;
            int row = i / 2;
            EditBox box = new EditBox(font, left + col * 134 + 18, y + row * 24, 112, 18,
                    Component.literal("Slot " + (i + 1)));
            box.setMaxLength(dev.nano.ndidisplays.block.LedPanelBlockEntity.MAX_SOURCE_NAME);
            box.setValue(values[i]);
            final int idx = i;
            box.setResponder(v -> values[idx] = v);
            addRenderableWidget(box);
            boxes.add(box);
        }
        int buttonsY = y + 4 * 24 + 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> save())
                .bounds(cx - 132, buttonsY, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, buttonsY, 130, 20).build());
    }

    private void save() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateScreenDmxSlotsPacket(
                target.getBlockPos(), List.of(values)));
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = width / 2;
        int left = cx - 132;
        graphics.drawCenteredString(font, title, cx, 12, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("gui.ndidisplays.screen_dmx.patch",
                        dmx.getUniverse(), dmx.getAddress()),
                cx, 26, 0xA0A0A0);
        for (int i = 0; i < ScreenDmxState.SLOTS; i++) {
            int col = i % 2;
            int row = i / 2;
            graphics.drawString(font, String.valueOf(i + 1),
                    left + col * 134 + 6, 44 + row * 24 + 5, 0xC0C0C0);
        }
        graphics.drawCenteredString(font,
                Component.translatable("gui.ndidisplays.screen_dmx.hint"),
                cx, height - 18, 0x808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
