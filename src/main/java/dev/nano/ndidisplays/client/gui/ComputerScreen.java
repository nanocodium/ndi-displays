package dev.nano.ndidisplays.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.ComputerBlockEntity;
import dev.nano.ndidisplays.client.computer.ComputerOS;
import dev.nano.ndidisplays.client.computer.Computers;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateComputerConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * Sitting at the computer: the live desktop fills the window, mouse and keyboard go straight to
 * the OS, and the machine's world-visible settings (name, resolution, FPS, NDI, lock) live in a
 * slim toolbar. The desktop keeps rendering — and broadcasting — after this screen closes; the
 * screen is a seat, not the machine.
 */
public class ComputerScreen extends Screen {

    private static final float PAGE_FRACTION = 0.86F;

    private final ComputerBlockEntity pc;

    private EditBox nameBox;
    private int resolution;
    private int fps;
    private boolean broadcast;
    private boolean locked;

    private int pageX;
    private int pageY;
    private int pageW;
    private int pageH;

    public ComputerScreen(ComputerBlockEntity pc) {
        super(Component.translatable("gui.ndidisplays.computer.title"));
        this.pc = pc;
        this.resolution = pc.getResolution();
        this.fps = pc.getFps();
        this.broadcast = pc.isBroadcasting();
        this.locked = pc.isLocked();
    }

    @Override
    protected void init() {
        int pad = 6;
        // Letterbox the desktop to the machine's own aspect: stretching a 16:9 screen across
        // whatever the Minecraft window happens to be distorted every pixel and every click.
        int availW = width - pad * 2;
        int availH = (int) (height * PAGE_FRACTION) - pad;
        double aspect = (double) pc.getWidth() / pc.getHeight();
        pageW = availW;
        pageH = (int) (pageW / aspect);
        if (pageH > availH) {
            pageH = availH;
            pageW = (int) (pageH * aspect);
        }
        pageX = (width - pageW) / 2;
        pageY = pad + (availH - pageH) / 2;

        int row = pad + availH + 6;
        nameBox = new EditBox(font, pad, row + 1, 130, 18,
                Component.translatable("gui.ndidisplays.computer.name"));
        nameBox.setMaxLength(ComputerBlockEntity.MAX_NAME);
        nameBox.setValue(pc.getName());
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.computer.rename"),
                        b -> send())
                .bounds(pad + 134, row, 60, 20).build());

        addRenderableWidget(CycleButton.<Integer>builder(r -> Component.literal(
                        ComputerBlockEntity.RES_W[r] + "x" + ComputerBlockEntity.RES_H[r]))
                .withValues(0, 1, 2)
                .withInitialValue(resolution)
                .displayOnlyValue()
                .create(pad + 198, row, 90, 20,
                        Component.translatable("gui.ndidisplays.web.resolution"),
                        (b, v) -> {
                            resolution = v;
                            send();
                        }));
        addRenderableWidget(CycleButton.<Integer>builder(f -> Component.literal(f + " fps"))
                .withValues(15, 24, 30, 60)
                .withInitialValue(fps)
                .displayOnlyValue()
                .create(pad + 292, row, 70, 20,
                        Component.translatable("gui.ndidisplays.web.fps"),
                        (b, v) -> {
                            fps = v;
                            send();
                        }));
        addRenderableWidget(CycleButton.onOffBuilder(broadcast)
                .create(pad + 366, row, 80, 20,
                        Component.translatable("gui.ndidisplays.computer.ndi"),
                        (b, v) -> {
                            broadcast = v;
                            send();
                        }));
        addRenderableWidget(CycleButton.onOffBuilder(locked)
                .create(pad + 450, row, 80, 20,
                        Component.translatable("gui.ndidisplays.computer.lock"),
                        (b, v) -> {
                            locked = v;
                            send();
                        }));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width - pad - 60, row, 60, 20).build());
    }

    private void send() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateComputerConfigPacket(
                pc.getBlockPos(), nameBox.getValue().trim(), resolution, fps, broadcast, locked));
        pc.applyComputerConfig(nameBox.getValue().trim(), resolution, fps, broadcast, locked);
    }

    // ------------------------------------------------------------------ input to the OS

    private ComputerOS os() {
        Computers.note(pc);
        return Computers.os(pc.getBlockPos());
    }

    private boolean overPage(double mx, double my) {
        return mx >= pageX && mx < pageX + pageW && my >= pageY && my < pageY + pageH;
    }

    private int osX(double mx) {
        return (int) ((mx - pageX) / pageW * Computers.logicalW(pc));
    }

    private int osY(double my) {
        return (int) ((my - pageY) / pageH * Computers.logicalH(pc));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (overPage(mx, my)) {
            ComputerOS os = os();
            if (os != null) {
                nameBox.setFocused(false);
                os.mouseDown(osX(mx), osY(my), button);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (overPage(mx, my)) {
            ComputerOS os = os();
            if (os != null) {
                os.mouseUp(osX(mx), osY(my), button);
                return true;
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (overPage(mx, my)) {
            ComputerOS os = os();
            if (os != null) {
                os.mouseMove(osX(mx), osY(my));
            }
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (overPage(mx, my)) {
            ComputerOS os = os();
            if (os != null) {
                os.scroll(amount);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, amount);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { // escape stands up from the desk; the machine keeps running
            onClose();
            return true;
        }
        if (nameBox.isFocused()) {
            if (key == 257 || key == 335) {
                send();
                nameBox.setFocused(false);
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        ComputerOS os = os();
        if (os != null) {
            os.keyDown(key, mods);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (nameBox.isFocused()) {
            return super.charTyped(c, mods);
        }
        ComputerOS os = os();
        if (os != null) {
            os.charTyped(c);
            return true;
        }
        return super.charTyped(c, mods);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Computers.note(pc);
        g.fill(pageX - 1, pageY - 1, pageX + pageW + 1, pageY + pageH + 1, 0xFF101014);

        int tex = Computers.textureId(pc.getBlockPos());
        if (tex != 0) {
            drawDesktop(g, tex);
        } else {
            g.drawCenteredString(font, Component.translatable("gui.ndidisplays.computer.booting"),
                    pageX + pageW / 2, pageY + pageH / 2 - 4, 0xFFB0B0B0);
        }
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(font, Component.translatable("gui.ndidisplays.web.source",
                        pc.getEffectiveSourceName()).getString(),
                pageX, height - 12, 0xFF9AA0A6, false);
    }

    private void drawDesktop(GuiGraphics g, int tex) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.disableBlend();
        Matrix4f mat = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // A render target's texture is bottom-up, so V is 0 at the quad's bottom.
        b.vertex(mat, pageX, pageY + pageH, 0).uv(0.0F, 0.0F).endVertex();
        b.vertex(mat, pageX + pageW, pageY + pageH, 0).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, pageX + pageW, pageY, 0).uv(1.0F, 1.0F).endVertex();
        b.vertex(mat, pageX, pageY, 0).uv(0.0F, 1.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
    }

    /** The computer keeps running (and broadcasting) while you sit at it — and after you leave. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
