package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable scrolling NDI source list: in-game camera rigs first (red live dot, always
 * selectable before network discovery catches up), then discovered NDI sources, plus
 * a manual refresh button. The host screen forwards {@code init}/{@code tick}/
 * {@code mouseScrolled}/{@code renderScrollbar} and receives picks via callback.
 */
public class NdiSourcePicker {

    public static final int ROW_HEIGHT = 18;
    private static final int LIST_WIDTH = 218;

    private final int rows;
    private final Consumer<AbstractWidget> add;
    private final Consumer<AbstractWidget> remove;
    private final Consumer<String> onPick;

    private final List<Button> buttons = new ArrayList<>();
    private List<String> allNames = new ArrayList<>();
    private List<String> cameraNames = new ArrayList<>();
    private int scroll;
    private int x;
    private int y;
    private int ticks;

    public NdiSourcePicker(int rows, Consumer<AbstractWidget> add, Consumer<AbstractWidget> remove,
                           Consumer<String> onPick) {
        this.rows = rows;
        this.add = add;
        this.remove = remove;
        this.onPick = onPick;
    }

    /** (Re)creates the list widgets at the given position. Call from the screen's init(). */
    public void init(int x, int y) {
        this.x = x;
        this.y = y;
        refreshNames();
        rebuild();
    }

    public int height() {
        return rows * ROW_HEIGHT;
    }

    /** The list follows discovery while the screen is open. */
    public void tick() {
        if (++ticks % 20 == 0) {
            List<String> before = allNames;
            refreshNames();
            if (!allNames.equals(before)) {
                rebuild();
            }
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= x && mouseX <= x + LIST_WIDTH + 8
                && mouseY >= y && mouseY <= y + rows * ROW_HEIGHT
                && allNames.size() > rows) {
            scroll -= (int) Math.signum(delta);
            rebuild();
            return true;
        }
        return false;
    }

    /** Scrollbar beside the list, shown when the list overflows its rows. */
    public void renderScrollbar(GuiGraphics graphics) {
        int total = allNames.size();
        if (total <= rows) {
            return;
        }
        int trackX = x + LIST_WIDTH + 2;
        int trackH = rows * ROW_HEIGHT - 2;
        graphics.fill(trackX, y, trackX + 4, y + trackH, 0xFF202020);
        int thumbH = Math.max(8, trackH * rows / total);
        int thumbY = y + (trackH - thumbH) * scroll / Math.max(1, total - rows);
        graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFFA0A0A0);
    }

    private void refreshNames() {
        List<String> cameras = CameraFeedManager.getLiveCameraNames();
        List<String> names = new ArrayList<>(cameras);
        // Web terminals are local senders like the rigs, so they can appear before NDI
        // discovery has caught up with them.
        for (String web : dev.nano.ndidisplays.client.CameraFeedManager.getWebTerminalNames()) {
            if (names.stream().noneMatch(web::contains)) {
                names.add(web);
            }
        }
        for (String discovered : NdiManager.getSourceNames()) {
            boolean isCamera = cameras.stream().anyMatch(discovered::contains);
            if (!isCamera) {
                names.add(discovered);
            }
        }
        cameraNames = cameras;
        allNames = names;
    }

    private void rebuild() {
        buttons.forEach(remove);
        buttons.clear();
        int maxScroll = Math.max(0, allNames.size() - rows);
        scroll = Math.min(Math.max(scroll, 0), maxScroll);
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            final String name = index < allNames.size() ? allNames.get(index) : null;
            final boolean isCamera = name != null && cameraNames.contains(name);
            Button b = Button.builder(
                            Component.literal(name != null ? (isCamera ? "§c●§r " + name : name) : "—"),
                            btn -> {
                                if (name != null) {
                                    onPick.accept(name);
                                }
                            })
                    .bounds(x, y + i * ROW_HEIGHT, LIST_WIDTH, 16).build();
            b.active = name != null;
            buttons.add(b);
            add.accept(b);
        }
        Button refresh = Button.builder(Component.literal("⟳"), btn -> {
                    refreshNames();
                    rebuild();
                })
                .bounds(x + LIST_WIDTH + 10, y, 32, 16).build();
        buttons.add(refresh);
        add.accept(refresh);
    }
}
