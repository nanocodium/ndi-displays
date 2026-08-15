package dev.nano.ndidisplays.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.WebTerminalBlockEntity;
import dev.nano.ndidisplays.client.web.WebBrowsers;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.UpdateWebTerminalPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * The terminal, as a screen you use: the live page filling most of the window, with a URL bar and
 * the output settings beneath it.
 *
 * Interaction goes through here rather than clicking the block face in the world. That is a
 * deliberate trade — a screen gives real text entry, cursor position and scrolling for free, all
 * of which would otherwise have to be reconstructed from ray-trace hits on a quad — and it is
 * also how a person actually uses a computer.
 *
 * Everything typed into the page is forwarded to Chromium; everything typed into the URL bar is
 * not, which is why the bar tracks focus explicitly rather than relying on where the mouse is.
 */
public class WebTerminalScreen extends Screen {

    /** Fraction of the window height given to the page; the rest is the toolbar. */
    private static final float PAGE_FRACTION = 0.84F;

    private final WebTerminalBlockEntity terminal;

    private EditBox urlBox;
    private String url;
    private String label;
    private int resolution;
    private int fps;
    private boolean broadcast;

    /** Page viewport in screen coordinates, recomputed on init and used to map the cursor. */
    private int pageX;
    private int pageY;
    private int pageW;
    private int pageH;

    public WebTerminalScreen(WebTerminalBlockEntity terminal) {
        super(Component.translatable("gui.ndidisplays.web.title"));
        this.terminal = terminal;
        this.url = terminal.getUrl();
        this.label = terminal.getLabel();
        this.resolution = terminal.getResolution();
        this.fps = terminal.getFps();
        this.broadcast = terminal.isBroadcasting();
    }

    @Override
    protected void init() {
        int pad = 6;
        pageX = pad;
        pageY = pad + 24;
        pageW = width - pad * 2;
        pageH = (int) (height * PAGE_FRACTION) - pad;

        urlBox = new EditBox(font, pad + 66, pad, width - pad * 2 - 150, 18,
                Component.translatable("gui.ndidisplays.web.url"));
        urlBox.setMaxLength(WebTerminalBlockEntity.MAX_URL);
        urlBox.setValue(url);
        urlBox.setResponder(v -> url = v);
        addRenderableWidget(urlBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.web.go"),
                        b -> navigate())
                .bounds(pad, pad, 60, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.web.reload"),
                        b -> {
                            WebBrowsers.Session s = current();
                            if (s != null) {
                                WebBrowsers.reload(s);
                            }
                        })
                .bounds(width - pad - 80, pad, 80, 18).build());

        int row = pageY + pageH + 6;
        addRenderableWidget(CycleButton.<Integer>builder(r -> Component.literal(
                        WebTerminalBlockEntity.RES_W[r] + "x" + WebTerminalBlockEntity.RES_H[r]))
                .withValues(0, 1, 2)
                .withInitialValue(resolution)
                .create(pad, row, 110, 20,
                        Component.translatable("gui.ndidisplays.web.resolution"),
                        (b, v) -> {
                            resolution = v;
                            send();
                        }));
        addRenderableWidget(CycleButton.<Integer>builder(f -> Component.literal(f + " fps"))
                .withValues(15, 24, 30, 60)
                .withInitialValue(fps)
                .create(pad + 116, row, 90, 20,
                        Component.translatable("gui.ndidisplays.web.fps"),
                        (b, v) -> {
                            fps = v;
                            send();
                        }));
        addRenderableWidget(CycleButton.onOffBuilder(broadcast)
                .create(pad + 212, row, 120, 20,
                        Component.translatable("gui.ndidisplays.web.broadcast"),
                        (b, v) -> {
                            broadcast = v;
                            send();
                        }));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width - pad - 80, row, 80, 20).build());
    }

    private WebBrowsers.Session current() {
        return WebBrowsers.session(terminal.getBlockPos(), url,
                WebTerminalBlockEntity.RES_W[resolution], WebTerminalBlockEntity.RES_H[resolution],
                terminal.getLevel() == null ? 0L : terminal.getLevel().getGameTime());
    }

    private void navigate() {
        String typed = urlBox.getValue().trim();
        // Bare hostnames are what people actually type; without a scheme CEF treats them as a
        // file path and shows nothing.
        if (!typed.isEmpty() && !typed.contains("://")) {
            typed = "https://" + typed;
            urlBox.setValue(typed);
        }
        url = typed;
        send();
        WebBrowsers.Session s = current();
        if (s != null) {
            WebBrowsers.reload(s);
        }
    }

    /** The server owns the configuration; it re-clamps and syncs it to everyone. */
    private void send() {
        NetworkHandler.CHANNEL.sendToServer(new UpdateWebTerminalPacket(
                terminal.getBlockPos(), url, label, resolution, fps, broadcast));
        // Applied locally too, so the page and the monitor respond without waiting on the trip.
        terminal.applyConfig(url, label, resolution, fps, broadcast);
    }

    // ------------------------------------------------------------------ page input
    //
    // Screen coordinates are mapped into the browser's own pixel space: the page is rendered at
    // its output resolution, not at the size it happens to be drawn, so a click has to be scaled
    // or it lands in the wrong place on any window that is not exactly 1280x720.

    private boolean overPage(double mx, double my) {
        return mx >= pageX && mx < pageX + pageW && my >= pageY && my < pageY + pageH;
    }

    private int pageMouseX(double mx) {
        return (int) ((mx - pageX) / pageW * WebTerminalBlockEntity.RES_W[resolution]);
    }

    private int pageMouseY(double my) {
        return (int) ((my - pageY) / pageH * WebTerminalBlockEntity.RES_H[resolution]);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (overPage(mx, my)) {
            WebBrowsers.Session s = current();
            if (s != null) {
                urlBox.setFocused(false);
                WebBrowsers.mouseMove(s, pageMouseX(mx), pageMouseY(my));
                WebBrowsers.mouseDown(s, pageMouseX(mx), pageMouseY(my), cefButton(button));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (overPage(mx, my)) {
            WebBrowsers.Session s = current();
            if (s != null) {
                WebBrowsers.mouseUp(s, pageMouseX(mx), pageMouseY(my), cefButton(button));
                return true;
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (overPage(mx, my)) {
            WebBrowsers.Session s = current();
            if (s != null) {
                WebBrowsers.mouseMove(s, pageMouseX(mx), pageMouseY(my));
            }
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (overPage(mx, my)) {
            WebBrowsers.Session s = current();
            if (s != null) {
                WebBrowsers.scroll(s, pageMouseX(mx), pageMouseY(my), amount);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, amount);
    }

    /** GLFW orders buttons left/right/middle; CEF expects left/middle/right. */
    private static int cefButton(int glfwButton) {
        return switch (glfwButton) {
            case 1 -> 2;
            case 2 -> 1;
            default -> 0;
        };
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Escape always leaves, even with the page focused — otherwise a page that swallows keys
        // would trap the player in the terminal.
        if (key == 256) {
            onClose();
            return true;
        }
        if (urlBox.isFocused()) {
            if (key == 257 || key == 335) {      // enter / numpad enter
                navigate();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        WebBrowsers.Session s = current();
        if (s != null) {
            WebBrowsers.keyDown(s, key, mods);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (!urlBox.isFocused()) {
            WebBrowsers.Session s = current();
            if (s != null) {
                WebBrowsers.keyUp(s, key, mods);
                return true;
            }
        }
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (urlBox.isFocused()) {
            return super.charTyped(c, mods);
        }
        WebBrowsers.Session s = current();
        if (s != null) {
            WebBrowsers.charTyped(s, c, mods);
            return true;
        }
        return super.charTyped(c, mods);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(pageX - 1, pageY - 1, pageX + pageW + 1, pageY + pageH + 1, 0xFF101014);

        WebBrowsers.Session s = current();
        int tex = s == null ? 0 : s.textureId();
        if (tex != 0) {
            drawPage(g, tex);
        } else {
            Component msg = WebBrowsers.available()
                    ? Component.translatable("gui.ndidisplays.web.loading")
                    : Component.translatable("gui.ndidisplays.web.no_mcef");
            g.drawCenteredString(font, msg, pageX + pageW / 2, pageY + pageH / 2 - 4, 0xFFB0B0B0);
        }
        super.render(g, mouseX, mouseY, partialTick);

        // The source name, so the operator knows what to select on a wall without guessing.
        g.drawString(font, Component.translatable("gui.ndidisplays.web.source",
                        terminal.getEffectiveSourceName()).getString(),
                pageX, pageY + pageH + 32, 0xFF9AA0A6, false);
    }

    /** Immediate-mode blit of Chromium's texture; there is no ResourceLocation to batch. */
    private void drawPage(GuiGraphics g, int tex) {
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.disableBlend();
        Matrix4f mat = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // CEF's image is top-down, so V is 0 at the top of the viewport.
        b.vertex(mat, pageX, pageY + pageH, 0).uv(0.0F, 1.0F).endVertex();
        b.vertex(mat, pageX + pageW, pageY + pageH, 0).uv(1.0F, 1.0F).endVertex();
        b.vertex(mat, pageX + pageW, pageY, 0).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, pageX, pageY, 0).uv(0.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
    }

    /** The page keeps loading and the feed keeps going out while the terminal is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
