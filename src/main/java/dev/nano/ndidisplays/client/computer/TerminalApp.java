package dev.nano.ndidisplays.client.computer;

import dev.nano.ndidisplays.client.ndi.NdiManager;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The terminal: a scrollback, a prompt, and commands that actually do things on this machine. */
class TerminalApp extends OsApp {

    private final List<String> lines = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private int scroll;

    TerminalApp(ComputerOS os) {
        super(os);
        lines.add("NDI Displays OS — " + os.name);
        lines.add("type 'help' for commands");
    }

    @Override
    String title() {
        return "Terminal";
    }

    @Override
    int preferredW() {
        return 280;
    }

    @Override
    int preferredH() {
        return 170;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, h, 0xFF0A0A0E);
        int visible = (h - 14) / 10;
        int first = Math.max(0, lines.size() - visible - scroll);
        int y = 3;
        for (int i = first; i < Math.min(lines.size(), first + visible); i++) {
            g.drawString(os.font, os.font.plainSubstrByWidth(lines.get(i), w - 6), 3, y,
                    0xFF6EE86E, false);
            y += 10;
        }
        String prompt = "$ " + input + (System.currentTimeMillis() % 1000 < 550 ? "_" : "");
        g.drawString(os.font, os.font.plainSubstrByWidth(prompt, w - 6), 3, h - 11,
                0xFFA0FFA0, false);
    }

    private void println(String s) {
        lines.add(s);
        scroll = 0;
    }

    private void run(String cmd) {
        println("$ " + cmd);
        String[] parts = cmd.trim().split("\\s+", 2);
        String c = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1] : "";
        switch (c) {
            case "" -> {
            }
            case "help" -> println("help echo date name sources files open <app> sysinfo clear");
            case "echo" -> println(arg);
            case "date" -> println(LocalDateTime.now().toString());
            case "name" -> println(os.name);
            case "sources" -> {
                List<String> names = NdiManager.getSourceNames();
                if (names.isEmpty()) {
                    println("(no NDI sources found)");
                } else {
                    names.forEach(this::println);
                }
            }
            case "files" -> {
                if (os.files.isEmpty()) {
                    println("(empty drive)");
                } else {
                    os.files.forEach(f -> println(f.name() + "  [" + f.kind() + "]"));
                }
            }
            case "open" -> {
                for (ComputerOS.AppKind k : ComputerOS.AppKind.values()) {
                    if (k.label.toLowerCase(Locale.ROOT).startsWith(arg.toLowerCase(Locale.ROOT))
                            && !arg.isEmpty()) {
                        os.open(k);
                        println("launched " + k.label);
                        return;
                    }
                }
                println("unknown app — apps: " + appList());
            }
            case "sysinfo" -> {
                println(os.name + " @ " + os.pos.toShortString());
                println("display " + os.width + "x" + os.height + " (logical)");
                println("drive: " + os.files.size() + " file(s)");
                println("os: NDI Displays OS, native renderer");
            }
            case "clear" -> {
                lines.clear();
                scroll = 0;
            }
            default -> println(c + ": command not found (try: help)");
        }
    }

    private String appList() {
        StringBuilder sb = new StringBuilder();
        for (ComputerOS.AppKind k : ComputerOS.AppKind.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k.label.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    @Override
    void keyDown(int key, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            run(input.toString());
            input.setLength(0);
        } else if (key == GLFW.GLFW_KEY_BACKSPACE && input.length() > 0) {
            input.deleteCharAt(input.length() - 1);
        }
    }

    @Override
    void charTyped(char c) {
        if (c >= ' ') {
            input.append(c);
        }
    }

    @Override
    void scroll(double amount) {
        scroll = ComputerOS.clamp(scroll + (int) Math.signum(amount) * 3, 0,
                Math.max(0, lines.size() - 3));
    }
}
