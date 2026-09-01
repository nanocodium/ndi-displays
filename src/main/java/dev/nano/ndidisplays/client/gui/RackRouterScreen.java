package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.RackBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.RackConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A rack router slot's patch panel: the published output name and the source feeding it. */
public class RackRouterScreen extends Screen {

    private final RackBlockEntity rack;
    private final int slot;

    private EditBox nameBox;
    private EditBox sourceBox;
    private NdiSourcePicker picker;

    public RackRouterScreen(RackBlockEntity rack, int slot) {
        super(Component.translatable("gui.ndidisplays.rack_router.title"));
        this.rack = rack;
        this.slot = slot;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 30;

        nameBox = new EditBox(font, left, y, 264, 18,
                Component.translatable("gui.ndidisplays.computer.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(rack.cfg(slot).getString("Name"));
        addRenderableWidget(nameBox);
        y += 26;

        sourceBox = new EditBox(font, left, y, 264, 18,
                Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(128);
        sourceBox.setValue(rack.cfg(slot).getString("Source"));
        addRenderableWidget(sourceBox);
        y += 22;

        picker = new NdiSourcePicker(4, this::addRenderableWidget, this::removeWidget,
                name -> sourceBox.setValue(name));
        picker.init(left, y);
        y += picker.height() + 12;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> {
                    send("Name", nameBox.getValue().trim());
                    send("Source", sourceBox.getValue().trim());
                    onClose();
                })
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
    }

    private void send(String key, String value) {
        NetworkHandler.CHANNEL.sendToServer(new RackConfigPacket(rack.getBlockPos(), slot, key, value));
        rack.cfg(slot).putString(key, value);
    }

    @Override
    public void tick() {
        super.tick();
        if (picker != null) {
            picker.tick();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (picker != null && picker.mouseScrolled(mx, my, delta)) {
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        g.drawString(font, "Output name (blank = automatic), and the source it forwards:",
                width / 2 - 132, 20, 0xFF9AA0B0, false);
        if (!rack.powered()) {
            g.drawString(font, Component.translatable("gui.ndidisplays.rack_web.no_power").getString(),
                    width / 2 - 132, height - 28, 0xFFE0A050, false);
        }
        g.drawString(font, NdiManager.getStatus(), width / 2 - 132, height - 16,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060, false);
        if (picker != null) {
            picker.renderScrollbar(g);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
