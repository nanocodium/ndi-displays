package dev.nano.ndidisplays.client.computer;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Settings: the machine's identity and the desktop's look. Resolution, FPS and the NDI toggle
 * live on the block's toolbar in Minecraft (they are world state, synced to everyone); wallpaper
 * and clock format are this desktop's own taste, kept on its drive.
 */
class SettingsApp extends OsApp {

    private static final String[] WALLPAPERS = {"Midnight Blue", "Purple Haze", "Deep Sea", "Plain"};

    SettingsApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return "Settings";
    }

    @Override
    int preferredW() {
        return 230;
    }

    @Override
    int preferredH() {
        return 150;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        g.drawString(os.font, "Computer", 6, 6, ComputerOS.C_DIM, false);
        g.drawString(os.font, os.font.plainSubstrByWidth(os.name, w - 12), 6, 17,
                ComputerOS.C_ACCENT, false);
        g.drawString(os.font, "display " + os.width + "x" + os.height + " (logical)", 6, 28,
                ComputerOS.C_TEXT, false);

        g.drawString(os.font, "Wallpaper", 6, 46, ComputerOS.C_DIM, false);
        g.fill(6, 57, 116, 70, ComputerOS.C_WIN2);
        g.drawString(os.font, WALLPAPERS[Math.floorMod(os.wallpaper, WALLPAPERS.length)]
                + "  >", 10, 60, ComputerOS.C_TEXT, false);

        g.drawString(os.font, "Clock", 6, 80, ComputerOS.C_DIM, false);
        g.fill(6, 91, 116, 104, ComputerOS.C_WIN2);
        g.drawString(os.font, (os.clock24 ? "24-hour" : "12-hour") + "  >", 10, 94,
                ComputerOS.C_TEXT, false);

        g.drawString(os.font, "Name, resolution, FPS and NDI:", 6, 116, ComputerOS.C_DIM, false);
        g.drawString(os.font, "the toolbar under this screen.", 6, 126, ComputerOS.C_DIM, false);
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (x >= 6 && x < 116 && y >= 57 && y < 70) {
            os.wallpaper = (os.wallpaper + 1) % WALLPAPERS.length;
            os.saveState();
        } else if (x >= 6 && x < 116 && y >= 91 && y < 104) {
            os.clock24 = !os.clock24;
            os.saveState();
        }
    }
}
