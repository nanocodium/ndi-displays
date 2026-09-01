package dev.nano.ndidisplays.client.computer;

import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A real text editor: multi-line buffer, movable cursor, typing, deletion, and Save straight
 * onto the computer's drive. No selections or clipboard yet — it is a notepad, not an IDE.
 */
class NotepadApp extends OsApp {

    private final List<StringBuilder> lines = new ArrayList<>(List.of(new StringBuilder()));
    private String fileName = "note.txt";
    private int row;
    private int col;
    private int scrollRow;
    private String status = "";
    private long statusUntil;

    NotepadApp(ComputerOS os) {
        super(os);
    }

    @Override
    String title() {
        return "Notes — " + fileName;
    }

    @Override
    int preferredW() {
        return 300;
    }

    @Override
    int preferredH() {
        return 200;
    }

    void load(String name, String content) {
        fileName = name;
        lines.clear();
        for (String l : content.split("\n", -1)) {
            lines.add(new StringBuilder(l));
        }
        row = 0;
        col = 0;
        scrollRow = 0;
    }

    private String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        // toolbar
        g.fill(0, 0, w, 14, ComputerOS.C_WIN2);
        g.fill(2, 2, 34, 12, ComputerOS.C_ACCENT);
        g.drawString(os.font, "Save", 6, 3, 0xFFFFFFFF, false);
        if (System.currentTimeMillis() < statusUntil) {
            g.drawString(os.font, status, 40, 3, ComputerOS.C_DIM, false);
        }

        int visible = (h - 16) / 10;
        scrollRow = ComputerOS.clamp(scrollRow, Math.max(0, row - visible + 1), row);
        for (int i = 0; i < visible && scrollRow + i < lines.size(); i++) {
            String l = lines.get(scrollRow + i).toString();
            g.drawString(os.font, os.font.plainSubstrByWidth(l, w - 8), 3, 16 + i * 10,
                    ComputerOS.C_TEXT, false);
        }
        // cursor (blinks on the half second)
        if (System.currentTimeMillis() % 1000 < 550 && row >= scrollRow
                && row < scrollRow + visible) {
            String before = lines.get(row).substring(0, Math.min(col, lines.get(row).length()));
            int cx = 3 + os.font.width(before);
            int cy = 16 + (row - scrollRow) * 10;
            g.fill(cx, cy, cx + 1, cy + 9, ComputerOS.C_ACCENT);
        }
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (y < 14) {
            if (x >= 2 && x < 34) {
                os.saveFile(fileName, "text", text());
                status = "saved to Files ✓";
                statusUntil = System.currentTimeMillis() + 1800;
            }
            return;
        }
        row = ComputerOS.clamp(scrollRow + (y - 16) / 10, 0, lines.size() - 1);
        String l = lines.get(row).toString();
        col = l.length();
        for (int i = 0; i <= l.length(); i++) {
            if (3 + os.font.width(l.substring(0, i)) >= x) {
                col = Math.max(0, i - (i > 0 ? 1 : 0));
                break;
            }
        }
    }

    @Override
    void scroll(double amount) {
        scrollRow = ComputerOS.clamp(scrollRow - (int) Math.signum(amount) * 3, 0,
                Math.max(0, lines.size() - 1));
        row = ComputerOS.clamp(row, 0, lines.size() - 1);
    }

    @Override
    void keyDown(int key, int mods) {
        StringBuilder line = lines.get(row);
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                String rest = line.substring(Math.min(col, line.length()));
                line.setLength(Math.min(col, line.length()));
                lines.add(row + 1, new StringBuilder(rest));
                row++;
                col = 0;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (col > 0) {
                    line.deleteCharAt(--col);
                } else if (row > 0) {
                    StringBuilder prev = lines.get(row - 1);
                    col = prev.length();
                    prev.append(line);
                    lines.remove(row--);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (col < line.length()) {
                    line.deleteCharAt(col);
                } else if (row < lines.size() - 1) {
                    line.append(lines.remove(row + 1));
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (col > 0) {
                    col--;
                } else if (row > 0) {
                    col = lines.get(--row).length();
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (col < line.length()) {
                    col++;
                } else if (row < lines.size() - 1) {
                    row++;
                    col = 0;
                }
            }
            case GLFW.GLFW_KEY_UP -> {
                if (row > 0) {
                    row--;
                    col = Math.min(col, lines.get(row).length());
                }
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (row < lines.size() - 1) {
                    row++;
                    col = Math.min(col, lines.get(row).length());
                }
            }
            case GLFW.GLFW_KEY_HOME -> col = 0;
            case GLFW.GLFW_KEY_END -> col = line.length();
            case GLFW.GLFW_KEY_S -> {
                if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) {
                    os.saveFile(fileName, "text", text());
                    status = "saved to Files ✓";
                    statusUntil = System.currentTimeMillis() + 1800;
                }
            }
            default -> {
            }
        }
    }

    @Override
    void charTyped(char c) {
        if (c >= ' ') {
            StringBuilder line = lines.get(row);
            line.insert(Math.min(col, line.length()), c);
            col++;
        }
    }
}
