package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.ProMonitorBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateProMonitorPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The production monitor's config: pick a source, set the panel brightness, done. */
public class ProMonitorScreen extends Screen {

    private final ProMonitorBlockEntity mon;

    private EditBox sourceBox;
    private NdiSourcePicker picker;
    private String source;
    private float brightness;

    public ProMonitorScreen(ProMonitorBlockEntity mon) {
        super(Component.translatable("gui.ndidisplays.pro_monitor.title"));
        this.mon = mon;
        this.source = mon.getSourceName();
        this.brightness = mon.getBrightness();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 132;
        int y = 30;

        sourceBox = new EditBox(font, left, y, 264, 18, Component.translatable("gui.ndidisplays.source"));
        sourceBox.setMaxLength(ProMonitorBlockEntity.MAX_SOURCE);
        sourceBox.setValue(source);
        sourceBox.setResponder(v -> source = v);
        addRenderableWidget(sourceBox);
        y += 22;

        picker = new NdiSourcePicker(4, this::addRenderableWidget, this::removeWidget, name -> {
            source = name;
            sourceBox.setValue(name);
        });
        picker.init(left, y);
        y += picker.height() + 12;

        addRenderableWidget(new AbstractSliderButton(left, y, 130, 18, Component.empty(),
                (brightness - 0.1) / 0.9) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.format("Brightness: %d%%",
                        Math.round((0.1 + value * 0.9) * 100))));
            }

            @Override
            protected void applyValue() {
                brightness = (float) (0.1 + value * 0.9);
            }
        });
        y += 26;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.winch.apply"), b -> {
                    NetworkHandler.CHANNEL.sendToServer(new UpdateProMonitorPacket(
                            mon.getBlockPos(), sourceBox.getValue().trim(), brightness));
                    mon.applyConfig(sourceBox.getValue().trim(), brightness);
                    onClose();
                })
                .bounds(cx - 132, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 130, 20).build());
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
        g.drawString(font, NdiManager.getStatus(), width / 2 - 132, height - 16,
                NdiManager.isAvailable() ? 0x60D060 : 0xE06060);
        if (picker != null) {
            picker.renderScrollbar(g);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
