package dev.nano.ndidisplays.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.block.RackBlockEntity;
import dev.nano.ndidisplays.client.web.WebBrowsers;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.net.RackConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * The rack web module's terminal: the module's page (the same Chromium surface its faceplate
 * shows) with a URL bar and full input — a web terminal that happens to live in a rack slot.
 * Needs rack power to run; without it the module politely says so.
 */
public class RackWebScreen extends Screen {

    private static final int PAGE_W = 640;
    private static final int PAGE_H = 360;

    private final RackBlockEntity rack;
    private final int slot;

    private EditBox urlBox;
    private int pageX;
    private int pageY;
    private int pageW;
    private int pageH;

    public RackWebScreen(RackBlockEntity rack, int slot) {
        super(Component.translatable("gui.ndidisplays.rack_web.title"));
        this.rack = rack;
        this.slot = slot;
    }

    @Override
    protected void init() {
        int pad = 6;
        int availW = width - pad * 2;
        int availH = (int) (height * 0.82F) - pad - 24;
        pageW = availW;
        pageH = pageW * PAGE_H / PAGE_W;
        if (pageH > availH) {
            pageH = availH;
            pageW = pageH * PAGE_W / PAGE_H;
        }
        pageX = (width - pageW) / 2;
        pageY = pad + 24 + (availH - pageH) / 2;

        urlBox = new EditBox(font, pad + 50, pad, width - pad * 2 - 50, 18,
                Component.translatable("gui.ndidisplays.web.url"));
        urlBox.setMaxLength(512);
        urlBox.setValue(rack.cfg(slot).getString("Url"));
        addRenderableWidget(urlBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.web.go"),
                        b -> navigate())
                .bounds(pad, pad, 46, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width - pad - 60, pageY + pageH + 6, 60, 20).build());
    }

    private void navigate() {
        String typed = urlBox.getValue().trim();
        if (!typed.isEmpty() && !typed.contains("://")) {
            typed = "https://" + typed;
            urlBox.setValue(typed);
        }
        NetworkHandler.CHANNEL.sendToServer(new RackConfigPacket(rack.getBlockPos(), slot, typed));
        rack.cfg(slot).putString("Url", typed);
        WebBrowsers.Session s = current();
        if (s != null) {
            WebBrowsers.reload(s);
        }
    }

    private WebBrowsers.Session current() {
        String url = rack.cfg(slot).getString("Url");
        if (url.isBlank() || !rack.powered()) {
            return null;
        }
        return WebBrowsers.session(rack.webKey(slot), url, PAGE_W, PAGE_H,
                rack.getLevel() == null ? 0L : rack.getLevel().getGameTime());
    }

    private boolean overPage(double mx, double my) {
        return mx >= pageX && mx < pageX + pageW && my >= pageY && my < pageY + pageH;
    }

    private int px(double mx) {
        return (int) ((mx - pageX) / pageW * PAGE_W);
    }

    private int py(double my) {
        return (int) ((my - pageY) / pageH * PAGE_H);
    }

    private static int cefButton(int b) {
        return switch (b) {
            case 1 -> 2;
            case 2 -> 1;
            default -> 0;
        };
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (overPage(mx, my)) {
            WebBrowsers.Session s = current();
            if (s != null) {
                urlBox.setFocused(false);
                WebBrowsers.mouseMove(s, px(mx), py(my));
                WebBrowsers.mouseDown(s, px(mx), py(my), cefButton(button));
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
                WebBrowsers.mouseUp(s, px(mx), py(my), cefButton(button));
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
                WebBrowsers.mouseMove(s, px(mx), py(my));
            }
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (overPage(mx, my)) {
            WebBrowsers.Session s = current();
            if (s != null) {
                WebBrowsers.scroll(s, px(mx), py(my), amount);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, amount);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) {
            onClose();
            return true;
        }
        if (urlBox.isFocused()) {
            if (key == 257 || key == 335) {
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(pageX - 1, pageY - 1, pageX + pageW + 1, pageY + pageH + 1, 0xFF101014);
        if (!rack.powered()) {
            g.drawCenteredString(font, Component.translatable("gui.ndidisplays.rack_web.no_power"),
                    pageX + pageW / 2, pageY + pageH / 2 - 4, 0xFFE0A050);
        } else {
            WebBrowsers.Session s = current();
            int tex = s == null ? 0 : s.textureId();
            if (tex != 0) {
                RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, tex);
                RenderSystem.disableBlend();
                Matrix4f mat = g.pose().last().pose();
                BufferBuilder b = Tesselator.getInstance().getBuilder();
                b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                b.vertex(mat, pageX, pageY + pageH, 0).uv(0.0F, 1.0F).endVertex();
                b.vertex(mat, pageX + pageW, pageY + pageH, 0).uv(1.0F, 1.0F).endVertex();
                b.vertex(mat, pageX + pageW, pageY, 0).uv(1.0F, 0.0F).endVertex();
                b.vertex(mat, pageX, pageY, 0).uv(0.0F, 0.0F).endVertex();
                BufferUploader.drawWithShader(b.end());
            } else {
                g.drawCenteredString(font, WebBrowsers.available()
                                ? Component.translatable("gui.ndidisplays.web.loading")
                                : Component.translatable("gui.ndidisplays.web.no_mcef"),
                        pageX + pageW / 2, pageY + pageH / 2 - 4, 0xFFB0B0B0);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
