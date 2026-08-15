package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.NdiRouterBlockEntity;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateRouterConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Router patch panel: name the output bus, then choose which source feeds it.
 * Receivers stay subscribed to the output name while the source behind it changes.
 */
public class RouterConfigScreen extends NdiPickerScreen {

    private final NdiRouterBlockEntity router;
    private EditBox outputBox;
    private String outputName;
    private int pattern;
    private int patternResolution;
    private int patternFps;

    public RouterConfigScreen(NdiRouterBlockEntity router) {
        super(Component.translatable("gui.ndidisplays.router.title"));
        this.router = router;
        this.outputName = router.getOutputName();
        this.pattern = router.getPattern();
        this.patternResolution = router.getPatternResolution();
        this.patternFps = router.getPatternFps();
        this.source = router.getSourceName();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 130;
        int y = 36;

        outputBox = new EditBox(font, left, y, 260, 20,
                Component.translatable("gui.ndidisplays.router.output"));
        outputBox.setMaxLength(NdiRouterBlockEntity.MAX_NAME);
        outputBox.setValue(outputName);
        outputBox.setHint(Component.literal(router.getEffectiveOutputName()));
        outputBox.setResponder(value -> outputName = value);
        addRenderableWidget(outputBox);
        y += 30;

        addSourcePicker(left, y, 260, NdiRouterBlockEntity.MAX_NAME,
                Component.translatable("gui.ndidisplays.router.source"));
        y += sourcePickerHeight();

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.router.clear"),
                        b -> {
                            source = "";
                            sourceBox.setValue("");
                        })
                .bounds(left, y, 128, 20).build());
        y += 26;

        // Generate instead of repatch: a router set to a pattern publishes its own picture, so a
        // wall can be lit and checked with no camera and no external source involved.
        addRenderableWidget(net.minecraft.client.gui.components.CycleButton.<Integer>builder(
                        pt -> Component.literal(
                                dev.nano.ndidisplays.client.ndi.TestPatternGenerator.patternName(pt)))
                .withValues(0, 1, 2, 3, 4)
                .withInitialValue(pattern)
                .create(width / 2 - 132, height - 78, 264, 20,
                        Component.translatable("gui.ndidisplays.router.pattern"),
                        (b, v) -> pattern = v));
        // Format of the generated picture. Only applies while a pattern is selected: forwarding
        // passes the upstream source through untouched, at whatever it happens to be.
        addRenderableWidget(net.minecraft.client.gui.components.CycleButton.<Integer>builder(
                        r -> Component.literal(NdiRouterBlockEntity.RES_W[r] + "x"
                                + NdiRouterBlockEntity.RES_H[r]))
                .withValues(0, 1, 2)
                .withInitialValue(patternResolution)
                .create(width / 2 - 132, height - 54, 130, 20,
                        Component.translatable("gui.ndidisplays.router.pattern_res"),
                        (b, v) -> patternResolution = v));
        addRenderableWidget(net.minecraft.client.gui.components.CycleButton.<Integer>builder(
                        f -> Component.literal(f + " fps"))
                .withValues(15, 24, 30, 60)
                .withInitialValue(patternFps)
                .create(width / 2 + 2, height - 54, 130, 20,
                        Component.translatable("gui.ndidisplays.router.pattern_fps"),
                        (b, v) -> patternFps = v));
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.apply"), b -> apply())
                .bounds(cx - 130, y, 128, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y, 128, 20).build());
    }

    private void apply() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateRouterConfigPacket(
                router.getBlockPos(), outputName.trim(), source.trim(), pattern,
                patternResolution, patternFps));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderSourceScrollbar(graphics);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        String status = NdiManager.isAvailable()
                ? "Output: " + router.getEffectiveOutputName()
                + (router.getSourceName().isBlank() ? "  (idle)" : "  ← " + router.getSourceName())
                : NdiManager.getStatus();
        graphics.drawCenteredString(font, Component.literal(status), width / 2, height - 28,
                NdiManager.isAvailable() ? 0x55FF55 : 0xFF5555);
    }
}
