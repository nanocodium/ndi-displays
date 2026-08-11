package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared NDI source picker for the screens that need one (LED processor, router).
 *
 * Provides the edit box plus a scrollable list of discovered sources that follows network
 * discovery while the screen is open. Live in-game camera rigs sort first with a red tally
 * dot, since they are usually what you want and may appear before discovery catches up.
 *
 * Subclasses call {@link #addSourcePicker} from {@code init()} and
 * {@link #renderSourceScrollbar} from {@code render()}.
 */
public abstract class NdiPickerScreen extends Screen {

    /** Visible rows; the full list scrolls behind them. */
    protected static final int SOURCE_ROWS = 3;
    protected static final int SOURCE_ROW_HEIGHT = 18;

    /**
     * The chosen source name. Held here rather than read back from the block entity so that
     * a widget rebuild (window resize, list refresh) never wipes half-typed input.
     */
    protected String source = "";

    protected EditBox sourceBox;

    private final List<Button> sourceWidgets = new ArrayList<>();
    private List<String> allNames = new ArrayList<>();
    private List<String> cameraNames = new ArrayList<>();
    private int sourceScroll;
    private int listLeft;
    private int listY;
    private int listWidth;
    private int ticks;

    protected NdiPickerScreen(Component title) {
        super(title);
    }

    /** Total height consumed by the picker, for laying out whatever comes below it. */
    protected static int sourcePickerHeight() {
        return 24 + SOURCE_ROWS * SOURCE_ROW_HEIGHT + 6;
    }

    protected void addSourcePicker(int left, int y, int width, int maxNameLength, Component label) {
        sourceBox = new EditBox(font, left, y, width, 20, label);
        sourceBox.setMaxLength(maxNameLength);
        sourceBox.setValue(source);
        sourceBox.setResponder(value -> source = value);
        addRenderableWidget(sourceBox);

        listLeft = left;
        listY = y + 24;
        listWidth = width;
        refreshNames();
        rebuildSourceButtons();
    }

    /** In-game rigs first, then routers, then everything else discovery has found. */
    private void refreshNames() {
        List<String> cameras = CameraFeedManager.getLiveCameraNames();
        List<String> names = new ArrayList<>(cameras);
        for (String router : dev.nano.ndidisplays.client.ndi.RouterManager.getRouterNames()) {
            if (names.stream().noneMatch(router::contains)) {
                names.add(router);
            }
        }
        for (String discovered : NdiManager.getSourceNames()) {
            if (names.stream().noneMatch(discovered::contains)) {
                names.add(discovered);
            }
        }
        cameraNames = cameras;
        allNames = names;
    }

    private void rebuildSourceButtons() {
        sourceWidgets.forEach(this::removeWidget);
        sourceWidgets.clear();
        int maxScroll = Math.max(0, allNames.size() - SOURCE_ROWS);
        sourceScroll = Math.min(Math.max(sourceScroll, 0), maxScroll);
        int rowWidth = listWidth - 42;
        for (int i = 0; i < SOURCE_ROWS; i++) {
            int index = sourceScroll + i;
            final String name = index < allNames.size() ? allNames.get(index) : null;
            boolean isCamera = name != null && cameraNames.contains(name);
            Button row = Button.builder(
                            Component.literal(name != null ? (isCamera ? "§c●§r " + name : name) : "—"),
                            btn -> {
                                if (name != null) {
                                    source = name;
                                    sourceBox.setValue(name);
                                }
                            })
                    .bounds(listLeft, listY + i * SOURCE_ROW_HEIGHT, rowWidth, 16).build();
            row.active = name != null;
            sourceWidgets.add(row);
            addRenderableWidget(row);
        }
        // Rebuilds only the picker, so typed input in the box survives a manual refresh.
        Button refresh = Button.builder(Component.literal("⟳"), btn -> {
                    refreshNames();
                    rebuildSourceButtons();
                })
                .bounds(listLeft + listWidth - 32, listY, 32, 16).build();
        sourceWidgets.add(refresh);
        addRenderableWidget(refresh);
    }

    /** Follows discovery while the screen is open, without disturbing the edit box. */
    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) {
            List<String> before = allNames;
            refreshNames();
            if (!allNames.equals(before)) {
                rebuildSourceButtons();
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= listLeft && mouseX <= listLeft + listWidth
                && mouseY >= listY && mouseY <= listY + SOURCE_ROWS * SOURCE_ROW_HEIGHT
                && allNames.size() > SOURCE_ROWS) {
            sourceScroll -= (int) Math.signum(delta);
            rebuildSourceButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** Draws the scroll indicator; only visible when the list overflows its rows. */
    protected void renderSourceScrollbar(GuiGraphics graphics) {
        int total = allNames.size();
        if (total <= SOURCE_ROWS) {
            return;
        }
        int trackX = listLeft + listWidth - 38;
        int trackH = SOURCE_ROWS * SOURCE_ROW_HEIGHT - 2;
        graphics.fill(trackX, listY, trackX + 4, listY + trackH, 0xFF202020);
        int thumbH = Math.max(8, trackH * SOURCE_ROWS / total);
        int thumbY = listY + (trackH - thumbH) * sourceScroll / Math.max(1, total - SOURCE_ROWS);
        graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFFA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
