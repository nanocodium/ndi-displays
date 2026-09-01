package dev.nano.ndidisplays.client.computer;

import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The NDI monitor: any live source on the network, in a desktop window. A control-room computer
 * watching a camera feed — and since this desktop is itself an NDI source, the watched feed goes
 * back OUT over NDI inside the desktop frame. Confidence monitoring, inside the game.
 */
class NdiMonitorApp extends OsApp {

    private String source = "";
    private boolean picking = true;
    private int scroll;

    NdiMonitorApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return source.isEmpty() ? "NDI Monitor" : "NDI — " + source;
    }

    @Override
    int preferredW() {
        return 300;
    }

    @Override
    int preferredH() {
        return 200;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, 14, ComputerOS.C_WIN2);
        g.fill(2, 2, 56, 12, ComputerOS.C_ACCENT);
        g.drawString(os.font, picking ? "Watching" : "Sources", 5, 3, 0xFFFFFFFF, false);
        g.drawString(os.font, os.font.plainSubstrByWidth(
                source.isEmpty() ? "no source selected" : source, w - 66), 62, 3,
                ComputerOS.C_DIM, false);

        if (picking) {
            List<String> names = NdiManager.getSourceNames();
            if (names.isEmpty()) {
                g.drawString(os.font, "no NDI sources on the network", 6, 24,
                        ComputerOS.C_DIM, false);
                return;
            }
            int y = 18 - scroll;
            for (String n : names) {
                if (y > 4 && y < h) {
                    g.drawString(os.font, (n.equals(source) ? "> " : "  ")
                            + os.font.plainSubstrByWidth(n, w - 14), 4, y,
                            n.equals(source) ? ComputerOS.C_ACCENT : ComputerOS.C_TEXT, false);
                }
                y += 12;
            }
            return;
        }

        g.fill(0, 14, w, h, 0xFF06060A);
        if (!source.isEmpty()) {
            NdiStream stream = NdiManager.acquire(source);
            int tex = 0;
            if (stream != null) {
                stream.uploadIfNeeded();
                tex = stream.getTextureId();
            }
            if (tex != 0) {
                ComputerOS.rawTexture(g, tex, 2, 16, w - 4, h - 18);
            } else {
                g.drawString(os.font, "no signal", w / 2 - 20, h / 2, ComputerOS.C_DIM, false);
            }
        }
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (y < 14) {
            if (x < 58) {
                picking = !picking;
            }
            return;
        }
        if (picking) {
            List<String> names = NdiManager.getSourceNames();
            int idx = (y + scroll - 18) / 12;
            if (idx >= 0 && idx < names.size()) {
                source = names.get(idx);
                picking = false;
            }
        }
    }

    @Override
    void scroll(double amount) {
        scroll = ComputerOS.clamp(scroll - (int) Math.signum(amount) * 24, 0, 400);
    }
}
