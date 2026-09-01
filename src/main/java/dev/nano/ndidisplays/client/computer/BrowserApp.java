package dev.nano.ndidisplays.client.computer;

import dev.nano.ndidisplays.client.web.WebBrowsers;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * The web browser: a native OS window hosting an embedded Chromium surface — the OS stays the
 * mod's own renderer, Chromium is just a program it runs, the way a real OS runs one. Requires
 * the MCEF companion mod; without it the window says so instead of pretending.
 *
 * The page renders at a fixed internal resolution and is scaled into the window; clicks and keys
 * are mapped through the same scale, so the cursor lands where it points at any window size.
 */
class BrowserApp extends OsApp {

    /** Internal page resolution: browser windows are small, this keeps text legible. */
    private static final int PAGE_W = 854;
    private static final int PAGE_H = 480;

    private static final int BAR_H = 14;

    private final StringBuilder url = new StringBuilder("https://duckduckgo.com/");
    private boolean urlFocused = true;
    private boolean navigate = true;
    private int viewW = 1;
    private int viewH = 1;

    BrowserApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return "Browser";
    }

    @Override
    int preferredW() {
        return 340;
    }

    @Override
    int preferredH() {
        return 230;
    }

    private WebBrowsers.Session session() {
        // Keyed by the computer's own block position: one Chromium per machine, closed with it.
        WebBrowsers.Session s = WebBrowsers.peek(os.pos);
        if (s == null && navigate) {
            s = WebBrowsers.session(os.pos, url.toString(), PAGE_W, PAGE_H,
                    os.mc.level == null ? 0L : os.mc.level.getGameTime());
            navigate = false;
        }
        return s;
    }

    private void go() {
        String typed = url.toString().trim();
        if (!typed.isEmpty() && !typed.contains("://")) {
            typed = typed.contains(".") && !typed.contains(" ")
                    ? "https://" + typed
                    : "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(typed,
                            java.nio.charset.StandardCharsets.UTF_8);
            url.setLength(0);
            url.append(typed);
        }
        WebBrowsers.Session s = WebBrowsers.session(os.pos, typed, PAGE_W, PAGE_H,
                os.mc.level == null ? 0L : os.mc.level.getGameTime());
        if (s != null) {
            WebBrowsers.reload(s);
        }
        urlFocused = false;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        viewW = w;
        viewH = h - BAR_H;
        // URL bar
        g.fill(0, 0, w, BAR_H, ComputerOS.C_WIN2);
        g.fill(0, 0, w - 26, BAR_H, urlFocused ? 0xFF101018 : ComputerOS.C_WIN2);
        String shown = url.toString();
        while (os.font.width(shown) > w - 44 && shown.length() > 1) {
            shown = shown.substring(1);
        }
        g.drawString(os.font, shown + (urlFocused && System.currentTimeMillis() % 1000 < 550
                ? "_" : ""), 3, 3, ComputerOS.C_TEXT, false);
        g.fill(w - 24, 1, w - 2, BAR_H - 1, ComputerOS.C_ACCENT);
        g.drawString(os.font, "Go", w - 19, 3, 0xFFFFFFFF, false);

        g.fill(0, BAR_H, w, h, 0xFFFFFFFF);
        if (!WebBrowsers.available()) {
            g.fill(0, BAR_H, w, h, 0xFF101018);
            g.drawString(os.font, "Requires the MCEF mod", 8, BAR_H + 10, ComputerOS.C_DIM, false);
            g.drawString(os.font, "(Chromium runtime)", 8, BAR_H + 22, ComputerOS.C_DIM, false);
            return;
        }
        WebBrowsers.Session s = session();
        int tex = s == null ? 0 : s.textureId();
        if (tex != 0) {
            // CEF's image is top-down, unlike the mod's own render targets: flipped draw.
            rawTextureTopDown(g, tex, 0, BAR_H, w, viewH);
        } else {
            g.fill(0, BAR_H, w, h, 0xFF101018);
            g.drawString(os.font, "loading…", 8, BAR_H + 10, ComputerOS.C_DIM, false);
        }
    }

    private static void rawTextureTopDown(GuiGraphics g, int tex, int x, int y, int w, int h) {
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.setShader(
                net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, tex);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        org.joml.Matrix4f mat = g.pose().last().pose();
        com.mojang.blaze3d.vertex.BufferBuilder b =
                com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        b.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        b.vertex(mat, x, y + h, 0).uv(0.0F, 1.0F).endVertex();
        b.vertex(mat, x + w, y + h, 0).uv(1.0F, 1.0F).endVertex();
        b.vertex(mat, x + w, y, 0).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, x, y, 0).uv(0.0F, 0.0F).endVertex();
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(b.end());
    }

    private int pageX(int x) {
        return x * PAGE_W / Math.max(1, viewW);
    }

    private int pageY(int y) {
        return (y - BAR_H) * PAGE_H / Math.max(1, viewH);
    }

    private static int cefButton(int b) {
        return switch (b) {
            case 1 -> 2;
            case 2 -> 1;
            default -> 0;
        };
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (y < BAR_H) {
            if (x >= viewW - 26) {
                go();
            } else {
                urlFocused = true;
            }
            return;
        }
        urlFocused = false;
        WebBrowsers.Session s = session();
        if (s != null) {
            WebBrowsers.mouseMove(s, pageX(x), pageY(y));
            WebBrowsers.mouseDown(s, pageX(x), pageY(y), cefButton(button));
        }
    }

    @Override
    void mouseUp(int x, int y, int button) {
        if (y < BAR_H) {
            return;
        }
        WebBrowsers.Session s = session();
        if (s != null) {
            WebBrowsers.mouseUp(s, pageX(x), pageY(y), cefButton(button));
        }
    }

    @Override
    void mouseMove(int x, int y) {
        if (y < BAR_H) {
            return;
        }
        WebBrowsers.Session s = WebBrowsers.peek(os.pos);
        if (s != null) {
            WebBrowsers.mouseMove(s, pageX(x), pageY(y));
        }
    }

    @Override
    void scroll(double amount) {
        WebBrowsers.Session s = WebBrowsers.peek(os.pos);
        if (s != null) {
            WebBrowsers.scroll(s, PAGE_W / 2, PAGE_H / 2, amount);
        }
    }

    @Override
    void keyDown(int key, int mods) {
        if (urlFocused) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                go();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && url.length() > 0) {
                url.deleteCharAt(url.length() - 1);
            } else if (key == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
                url.append(os.mc.keyboardHandler.getClipboard());
            }
            return;
        }
        WebBrowsers.Session s = WebBrowsers.peek(os.pos);
        if (s != null) {
            WebBrowsers.keyDown(s, key, mods);
            WebBrowsers.keyUp(s, key, mods);
        }
    }

    @Override
    void charTyped(char c) {
        if (urlFocused) {
            if (c >= ' ') {
                url.append(c);
            }
            return;
        }
        WebBrowsers.Session s = WebBrowsers.peek(os.pos);
        if (s != null) {
            WebBrowsers.charTyped(s, c, 0);
        }
    }

    @Override
    void onClose() {
        WebBrowsers.close(os.pos);
    }
}
