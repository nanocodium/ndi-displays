package dev.nano.ndidisplays.client.computer;

import net.minecraft.client.gui.GuiGraphics;

/** The drive: everything saved on this computer, opened by the app that made it. */
class FilesApp extends OsApp {

    private int scroll;

    FilesApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return "Files";
    }

    @Override
    int preferredW() {
        return 220;
    }

    @Override
    int preferredH() {
        return 170;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        if (os.files.isEmpty()) {
            g.drawString(os.font, "Empty drive.", 6, 8, ComputerOS.C_DIM, false);
            g.drawString(os.font, "Save from Notes or Paint.", 6, 20, ComputerOS.C_DIM, false);
            return;
        }
        int y = 3 - scroll;
        for (ComputerOS.OsFile f : os.files) {
            if (y > -12 && y < h) {
                String tag = f.kind().equals("image") ? "[#]" : "[=]";
                g.drawString(os.font, tag + " "
                        + os.font.plainSubstrByWidth(f.name(), w - 40), 4, y,
                        ComputerOS.C_TEXT, false);
                g.drawString(os.font, "x", w - 10, y, ComputerOS.C_RED, false);
            }
            y += 12;
        }
    }

    @Override
    void mouseDown(int x, int y, int button) {
        int idx = (y + scroll - 3) / 12;
        if (idx < 0 || idx >= os.files.size()) {
            return;
        }
        ComputerOS.OsFile f = os.files.get(idx);
        if (x >= preferredW() - 24) { // the delete cross column, generous hitbox
            os.files.remove(idx);
            os.saveState();
            return;
        }
        if (f.kind().equals("image")) {
            os.openImageFile(f.data());
        } else {
            os.openTextFile(f.name(), f.data());
        }
    }

    @Override
    void scroll(double amount) {
        scroll = ComputerOS.clamp(scroll - (int) Math.signum(amount) * 24, 0,
                Math.max(0, os.files.size() * 12 - 40));
    }
}
