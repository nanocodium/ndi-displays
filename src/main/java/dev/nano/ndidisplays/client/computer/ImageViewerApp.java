package dev.nano.ndidisplays.client.computer;

import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * The image viewer: type or paste a URL (or open a saved painting from Files), and the picture is
 * fetched off-thread and shown fitted to the window. Save files the URL on the drive.
 */
class ImageViewerApp extends OsApp {

    private final StringBuilder url = new StringBuilder();
    private int texId;
    private String info = "enter an image URL and press Enter";
    private boolean loading;

    ImageViewerApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return "Images";
    }

    @Override
    int preferredW() {
        return 280;
    }

    @Override
    int preferredH() {
        return 190;
    }

    void show(String u) {
        url.setLength(0);
        url.append(u);
        fetch();
    }

    private void fetch() {
        String u = url.toString().trim();
        if (u.isEmpty() || loading) {
            return;
        }
        loading = true;
        info = "loading…";
        os.fetchImage(u, (tex, msg) -> {
            loading = false;
            texId = tex;
            info = tex == 0 ? msg : msg.split(" {2}")[0];
        });
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, 14, ComputerOS.C_WIN2);
        String shown = url.toString();
        // keep the tail visible while typing long URLs
        while (os.font.width(shown) > w - 60 && shown.length() > 1) {
            shown = shown.substring(1);
        }
        g.drawString(os.font, shown + (System.currentTimeMillis() % 1000 < 550 ? "_" : ""),
                3, 3, ComputerOS.C_TEXT, false);
        g.fill(w - 28, 2, w - 2, 12, ComputerOS.C_ACCENT);
        g.drawString(os.font, "Save", w - 26, 3, 0xFFFFFFFF, false);

        g.fill(0, 14, w, h, 0xFF0A0A0E);
        if (texId != 0) {
            // fit inside, preserve nothing fancy: the window is the frame
            ComputerOS.rawTexture(g, texId, 2, 16, w - 4, h - 18);
        } else {
            g.drawString(os.font, os.font.plainSubstrByWidth(info, w - 8), 4, h / 2,
                    ComputerOS.C_DIM, false);
        }
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (y < 14 && x >= preferredW() - 30) {
            String u = url.toString().trim();
            if (!u.isEmpty()) {
                os.saveFile(u.substring(u.lastIndexOf('/') + 1).replaceAll("[?#].*", "")
                        .replaceAll("[^A-Za-z0-9._-]", "_"), "image", u);
                info = "saved to Files ✓";
            }
        }
    }

    @Override
    void keyDown(int key, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            fetch();
        } else if (key == GLFW.GLFW_KEY_BACKSPACE && url.length() > 0) {
            url.deleteCharAt(url.length() - 1);
        } else if (key == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            url.append(os.mc.keyboardHandler.getClipboard());
        }
    }

    @Override
    void charTyped(char c) {
        if (c >= ' ') {
            url.append(c);
        }
    }
}
