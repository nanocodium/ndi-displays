package dev.nano.ndidisplays.client.computer;

import net.minecraft.client.gui.GuiGraphics;

/**
 * One application on the computer. Renders into its window's client area (origin 0,0, given
 * size) and receives input in the same coordinates. Everything here is native Minecraft GUI
 * code — an app is just a widget that lives on a virtual desktop instead of a real screen.
 */
abstract class OsApp {

    protected final ComputerOS os;

    OsApp(ComputerOS os) {
        this.os = os;
    }

    abstract String title();

    int preferredW() {
        return 240;
    }

    int preferredH() {
        return 160;
    }

    abstract void render(GuiGraphics g, int w, int h);

    void mouseDown(int x, int y, int button) {
    }

    void mouseUp(int x, int y, int button) {
    }

    void mouseMove(int x, int y) {
    }

    void scroll(double amount) {
    }

    void keyDown(int key, int mods) {
    }

    void charTyped(char c) {
    }

    void onClose() {
    }
}
