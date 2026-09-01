package dev.nano.ndidisplays.client.computer;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The music player. The library is the game's own record collection, played positionally at the
 * computer block through the sound engine — the machine is the speaker, and everyone near it in
 * the world hears the same song, like a jukebox that grew a UI.
 */
class MusicApp extends OsApp {

    private int scroll;

    MusicApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return "Music";
    }

    @Override
    int preferredW() {
        return 230;
    }

    @Override
    int preferredH() {
        return 190;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        // now playing bar
        g.fill(0, 0, w, 16, ComputerOS.C_WIN2);
        if (os.nowPlayingIdx >= 0 && os.discPlaying()) {
            g.drawString(os.font, "> " + os.font.plainSubstrByWidth(
                    (String) ComputerOS.DISCS[os.nowPlayingIdx][0], w - 46), 4, 4,
                    ComputerOS.C_ACCENT, false);
            g.fill(w - 38, 2, w - 4, 14, ComputerOS.C_RED);
            g.drawString(os.font, "Stop", w - 34, 4, 0xFFFFFFFF, false);
        } else {
            g.drawString(os.font, "stopped — pick a record", 4, 4, ComputerOS.C_DIM, false);
        }

        int y = 20 - scroll;
        for (int i = 0; i < ComputerOS.DISCS.length; i++) {
            if (y > 6 && y < h) {
                boolean current = i == os.nowPlayingIdx;
                g.drawString(os.font, (current ? "> " : "  ") + os.font.plainSubstrByWidth(
                        (String) ComputerOS.DISCS[i][0], w - 14), 4, y,
                        current ? ComputerOS.C_ACCENT : ComputerOS.C_TEXT, false);
            }
            y += 12;
        }
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (y < 16) {
            if (x >= preferredW() - 42) {
                os.stopDisc();
            }
            return;
        }
        int idx = (y + scroll - 20) / 12;
        if (idx >= 0 && idx < ComputerOS.DISCS.length) {
            os.playDisc(idx);
        }
    }

    /** Closing the player stops the record, like quitting a real music app. */
    @Override
    void onClose() {
        os.stopDisc();
    }

    @Override
    void scroll(double amount) {
        scroll = ComputerOS.clamp(scroll - (int) Math.signum(amount) * 24, 0,
                Math.max(0, ComputerOS.DISCS.length * 12 - 100));
    }
}
