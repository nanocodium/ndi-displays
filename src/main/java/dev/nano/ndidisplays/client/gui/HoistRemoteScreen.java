package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.hoist.HoistGroupSnapshot;
import dev.nano.ndidisplays.hoist.HoistStatus;
import dev.nano.ndidisplays.net.HoistGroupListPacket;
import dev.nano.ndidisplays.net.HoistRemotePacket;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.Locale;

/**
 * The radio remote's belly-box.
 *
 * Drawn rather than skinned, because a pendant is a shape before it is a set of controls:
 * a yellow body you can find on a dark stage, a mushroom stop your palm hits without
 * looking, and two big keys that are the only things your thumb has to distinguish. The
 * ordering is deliberate — the emergency stop is nowhere near the direction keys, so the
 * button that must never be pressed by accident and the buttons pressed constantly do not
 * share a neighbourhood.
 *
 * The screen owns no state beyond the selection. Everything shown arrives from the server
 * in a {@link HoistGroupListPacket}, and every press leaves as a
 * {@link HoistRemotePacket}; the remote is a keypad, not a controller.
 */
public class HoistRemoteScreen extends Screen {

    // --- Body ---
    private static final int PANEL_W = 152;
    private static final int PANEL_H = 300;

    private static final int BEZEL = 0xFF141517;
    private static final int SHELL = 0xFF25272C;
    private static final int FACE = 0xFFF5CE1F;
    private static final int FACE_EDGE = 0xFFB99B10;
    private static final int PLATE = 0xFF121316;
    private static final int PLATE_EDGE = 0xFF3A3D42;
    private static final int KEY = 0xFF1B1C1F;
    private static final int KEY_EDGE = 0xFF3C4045;
    private static final int KEY_HOVER = 0xFF2A2C31;
    private static final int KEY_DOWN = 0xFF0B0C0D;
    private static final int STOP_RED = 0xFFD8232A;
    private static final int STOP_RED_DARK = 0xFF6E0F13;
    private static final int TEXT_ON_FACE = 0xFF1A1A12;
    private static final int TEXT_ON_PLATE = 0xFFD8DEE4;
    private static final int TEXT_DIM = 0xFF7C848C;

    /** The server pushes a fresh snapshot on every press; this covers everything else. */
    private static final int POLL_INTERVAL = 10;

    private final InteractionHand hand;
    private List<HoistGroupSnapshot> groups;
    private int index;
    private int pollTimer;
    /** The mushroom stays down until it is twisted off — same latch the item holds. */
    private boolean estop;
    /** Kept so its legend can follow the rig state without rebuilding the whole pendant. */
    private Key loadKey;

    public HoistRemoteScreen(InteractionHand hand, List<HoistGroupSnapshot> groups,
                             String selected, boolean estop) {
        super(Component.translatable("gui.ndidisplays.remote.title"));
        this.hand = hand;
        this.groups = groups;
        this.index = indexOf(groups, selected);
        this.estop = estop;
    }

    // ------------------------------------------------------------------ server updates

    /** Applies a snapshot from the server, opening the pendant if it is not up yet. */
    public static void accept(HoistGroupListPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof HoistRemoteScreen open) {
            // Groups can appear and disappear between snapshots, so the selection is
            // carried by name. Read it before the old list is thrown away.
            String selected = open.selectedName();
            open.groups = msg.groups();
            open.index = indexOf(msg.groups(), selected);
            open.estop = msg.estop();
            open.refreshLoadKey();
        } else if (msg.open()) {
            mc.setScreen(new HoistRemoteScreen(msg.handEnum(), msg.groups(), msg.selected(),
                    msg.estop()));
        }
    }

    private static int indexOf(List<HoistGroupSnapshot> groups, String name) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).name().equals(name)) {
                return i;
            }
        }
        return 0;
    }

    private HoistGroupSnapshot current() {
        return index >= 0 && index < groups.size() ? groups.get(index) : null;
    }

    private String selectedName() {
        HoistGroupSnapshot group = current();
        return group == null ? HoistGroupSnapshot.ALL : group.name();
    }

    // ------------------------------------------------------------------ layout

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return Math.max(0, (height - PANEL_H) / 2);
    }

    @Override
    protected void init() {
        int x = left();
        int y = top();

        addRenderableWidget(new EStop(x + 90, y + 6, this::toggleEStop));

        addRenderableWidget(new Key(x + 20, y + 86, 54, 38, Glyph.UP, FACE,
                Component.translatable("gui.ndidisplays.remote.up"),
                () -> sendTravel(HoistRemotePacket.Action.UP)));
        addRenderableWidget(new Key(x + 78, y + 86, 54, 38, Glyph.DOWN, FACE,
                Component.translatable("gui.ndidisplays.remote.down"),
                () -> sendTravel(HoistRemotePacket.Action.DOWN)));

        addRenderableWidget(new Key(x + 20, y + 126, 112, 32, Glyph.LABEL, STOP_RED,
                Component.translatable("gui.ndidisplays.remote.stop"),
                () -> send(HoistRemotePacket.Action.STOP)));

        loadKey = new Key(x + 20, y + 164, 112, 24, Glyph.LABEL, 0xFFD8DEE4,
                loadKeyLabel(),
                () -> sendTravel(anyAttached()
                        ? HoistRemotePacket.Action.DETACH
                        : HoistRemotePacket.Action.ATTACH));
        addRenderableWidget(loadKey);

        addRenderableWidget(new Key(x + 20, y + 208, 24, 22, Glyph.LEFT, FACE,
                Component.empty(), () -> cycle(-1)));
        addRenderableWidget(new Key(x + 108, y + 208, 24, 22, Glyph.RIGHT, FACE,
                Component.empty(), () -> cycle(1)));
    }

    private boolean anyAttached() {
        HoistGroupSnapshot group = current();
        return group != null && group.attached() > 0;
    }

    private Component loadKeyLabel() {
        return Component.translatable(anyAttached()
                ? "gui.ndidisplays.remote.drop"
                : "gui.ndidisplays.remote.pick");
    }

    private void refreshLoadKey() {
        if (loadKey != null) {
            loadKey.setMessage(loadKeyLabel());
        }
    }

    private void cycle(int delta) {
        if (groups.isEmpty()) {
            return;
        }
        index = Math.floorMod(index + delta, groups.size());
        send(HoistRemotePacket.Action.SELECT);
        rebuildWidgets();
    }

    private void send(HoistRemotePacket.Action action) {
        NetworkHandler.CHANNEL.sendToServer(
                HoistRemotePacket.of(action, selectedName(), hand));
    }

    /** Direction and load keys: the mushroom eats these while it is latched. */
    private void sendTravel(HoistRemotePacket.Action action) {
        if (estop) {
            return;
        }
        send(action);
    }

    /** Push to latch and cut everything; a second press twists the mushroom off. */
    private void toggleEStop() {
        if (estop) {
            estop = false;
            send(HoistRemotePacket.Action.RELEASE_ESTOP);
        } else {
            estop = true;
            send(HoistRemotePacket.Action.ESTOP);
        }
    }

    /** Industrial beacon: two beats on, one off, read off the wall clock so it stays even. */
    private static boolean blinkOn() {
        return (Util.getMillis() / 280L) % 3L != 2L;
    }

    private static int lerpArgb(int from, int to, float t) {
        float u = Math.max(0.0F, Math.min(1.0F, t));
        int a = (int) (((from >>> 24) & 0xFF) + ((((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * u));
        int r = (int) (((from >>> 16) & 0xFF) + ((((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * u));
        int g = (int) (((from >>> 8) & 0xFF) + ((((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * u));
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * u));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public void tick() {
        // A rig moves for tens of seconds at a time; without this the readout would sit
        // frozen at whatever it said when the last button was pressed.
        if (++pollTimer >= POLL_INTERVAL) {
            pollTimer = 0;
            send(HoistRemotePacket.Action.POLL);
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int x = left();
        int y = top();

        // --- Shell -----------------------------------------------------------
        roundRect(g, x, y, PANEL_W, PANEL_H, BEZEL, 12);
        roundRect(g, x + 3, y + 3, PANEL_W - 6, PANEL_H - 6, SHELL, 10);
        // Strain relief where the cable would leave a real pendant.
        roundRect(g, x + PANEL_W / 2 - 9, y + PANEL_H - 6, 18, 10, BEZEL, 4);

        // --- Yellow face ------------------------------------------------------
        roundRect(g, x + 9, y + 43, PANEL_W - 18, PANEL_H - 60, FACE_EDGE, 8);
        roundRect(g, x + 10, y + 44, PANEL_W - 20, PANEL_H - 63, FACE, 8);

        HoistGroupSnapshot group = current();
        HoistStatus status = group == null ? HoistStatus.STOPPED : group.statusEnum();

        // --- Status strip -----------------------------------------------------
        int stripY = y + 48;
        boolean alarm = estop && blinkOn();
        int strip = estop ? (alarm ? 0xFF3A0C10 : PLATE) : PLATE;
        int stripEdge = estop ? (alarm ? 0xFFE8383E : 0xFF6E0F13) : PLATE_EDGE;
        roundRect(g, x + 18, stripY, 116, 18, stripEdge, 4);
        roundRect(g, x + 19, stripY + 1, 114, 16, strip, 4);
        int lampColour = estop ? (alarm ? 0xFFFF3A40 : 0xFF6E0F13) : status.colour();
        lamp(g, x + 29, stripY + 9, lampColour);
        g.drawString(font, Component.translatable(estop
                        ? "gui.ndidisplays.remote.status.estop"
                        : status.translationKey()),
                x + 38, stripY + 5, estop ? lampColour : status.colour(), false);

        // --- Key legends: sit in the gap above the keys, not on their top edge. ---
        label(g, x + 20, y + 68, 54, "gui.ndidisplays.remote.up");
        label(g, x + 78, y + 68, 54, "gui.ndidisplays.remote.down");

        // --- Group selector ---------------------------------------------------
        int selY = y + 208;
        g.drawString(font, Component.translatable("gui.ndidisplays.remote.group"),
                x + 20, selY - 10, TEXT_ON_FACE, false);
        roundRect(g, x + 46, selY, 60, 22, PLATE_EDGE, 3);
        roundRect(g, x + 47, selY + 1, 58, 20, PLATE, 3);
        String name = group == null || group.isAll()
                ? Component.translatable("gui.ndidisplays.remote.all_groups").getString()
                : group.name();
        g.drawCenteredString(font, trim(name, 54), x + 76, selY + 7, 0xFFF5CE1F);

        // --- Readout ----------------------------------------------------------
        int readY = y + 240;
        roundRect(g, x + 18, readY, 116, 46, PLATE_EDGE, 3);
        roundRect(g, x + 19, readY + 1, 114, 44, PLATE, 3);
        if (group == null) {
            g.drawString(font, Component.translatable("gui.ndidisplays.remote.no_motors"),
                    x + 25, readY + 18, TEXT_DIM, false);
        } else {
            readout(g, x + 25, readY + 6, "gui.ndidisplays.remote.motors",
                    group.loaded() + " / " + group.motors());
            readout(g, x + 25, readY + 19, "gui.ndidisplays.hoist.chain",
                    String.format(Locale.ROOT, "%.2f m", group.chain()));
            readout(g, x + 25, readY + 32, "gui.ndidisplays.hoist.load",
                    group.attached() == 0
                            ? Component.translatable("gui.ndidisplays.remote.no_load").getString()
                            : Component.translatable("gui.ndidisplays.remote.load_info",
                                    group.loadBlocks(), group.attached()).getString());
        }

        super.render(g, mouseX, mouseY, partialTick);

        // --- Nameplate --------------------------------------------------------
        g.drawString(font, Component.translatable("gui.ndidisplays.remote.brand"),
                x + 14, y + 16, 0xFF6E757C, false);
    }

    private void label(GuiGraphics g, int x, int y, int w, String key) {
        g.drawCenteredString(font, Component.translatable(key), x + w / 2, y, TEXT_ON_FACE);
    }

    private void readout(GuiGraphics g, int x, int y, String labelKey, String value) {
        Component label = Component.translatable(labelKey);
        g.drawString(font, label, x, y, TEXT_DIM, false);
        g.drawString(font, value, x + font.width(label) + 5, y, TEXT_ON_PLATE, false);
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && font.width(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing helpers

    /** Filled rectangle with rounded corners, built row by row off a circle. */
    private static void roundRect(GuiGraphics g, int x, int y, int w, int h, int colour, int r) {
        int radius = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < h; i++) {
            int edge = Math.min(i, h - 1 - i);
            int inset = 0;
            if (edge < radius) {
                double dy = radius - edge - 0.5;
                inset = radius - (int) Math.round(Math.sqrt(radius * radius - dy * dy));
            }
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, colour);
        }
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int colour) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, colour);
        }
    }

    private static void lamp(GuiGraphics g, int cx, int cy, int colour) {
        disc(g, cx, cy, 5, 0xFF000000);
        disc(g, cx, cy, 4, colour);
        g.fill(cx - 1, cy - 2, cx + 1, cy - 1, 0x60FFFFFF);
    }

    /** Vertical triangle: the up and down keys' only marking. */
    private static void triangleV(GuiGraphics g, int cx, int cy, int halfW, int h,
                                  boolean up, int colour) {
        for (int i = 0; i < h; i++) {
            int w = Math.max(1, Math.round(halfW * (i + 1f) / h));
            int row = up ? cy - h / 2 + i : cy + h / 2 - i - 1;
            g.fill(cx - w, row, cx + w, row + 1, colour);
        }
    }

    private static void triangleH(GuiGraphics g, int cx, int cy, int w, int halfH,
                                  boolean leftward, int colour) {
        for (int i = 0; i < w; i++) {
            int h = Math.max(1, Math.round(halfH * (i + 1f) / w));
            int col = leftward ? cx - w / 2 + i : cx + w / 2 - i - 1;
            g.fill(col, cy - h, col + 1, cy + h, colour);
        }
    }

    // ------------------------------------------------------------------ widgets

    private enum Glyph { UP, DOWN, LEFT, RIGHT, LABEL }

    /** A rubber key: dark, slightly domed, with one marking. */
    private class Key extends AbstractButton {

        private final Glyph glyph;
        private final int glyphColour;
        private final Runnable action;

        Key(int x, int y, int w, int h, Glyph glyph, int glyphColour, Component label,
            Runnable action) {
            super(x, y, w, h, label);
            this.glyph = glyph;
            this.glyphColour = glyphColour;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean pressed = isFocused() && isHovered;

            roundRect(g, x, y, w, h, KEY_EDGE, 4);
            roundRect(g, x + 1, y + 1, w - 2, h - 2,
                    pressed ? KEY_DOWN : (isHovered ? KEY_HOVER : KEY), 4);
            if (!pressed) {
                // A thin lit top edge is what makes a flat rectangle read as a raised key.
                g.fill(x + 4, y + 1, x + w - 4, y + 2, 0x30FFFFFF);
            }

            int cx = x + w / 2;
            int cy = y + h / 2;
            boolean locked = estop && (glyph == Glyph.UP || glyph == Glyph.DOWN
                    || glyph == Glyph.LABEL && glyphColour != STOP_RED);
            int mark = locked ? 0xFF4A4E54 : glyphColour;
            switch (glyph) {
                case UP -> triangleV(g, cx, cy, 9, 11, true, mark);
                case DOWN -> triangleV(g, cx, cy, 9, 11, false, mark);
                case LEFT -> triangleH(g, cx, cy, 8, 6, true, glyphColour);
                case RIGHT -> triangleH(g, cx, cy, 8, 6, false, glyphColour);
                case LABEL -> g.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                        cx, cy - 4, mark);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * The mushroom: push to latch, click again to twist off.
     *
     * Drawn as a raised dome while idle. Once down it sinks into the collar, loses its
     * highlight, and pulses so the operator can see the remote is dead from across a room.
     */
    private class EStop extends AbstractButton {

        private static final int R = 18;

        private final Runnable action;

        EStop(int x, int y, Runnable action) {
            super(x, y, R * 2 + 2, R * 2 + 2, Component.translatable("gui.ndidisplays.remote.estop"));
            this.action = action;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int cx = getX() + R + 1;
            int cy = getY() + R + 1;
            boolean down = estop;
            boolean alarm = down && blinkOn();
            float pulse = down
                    ? 0.45F + 0.55F * (float) Math.abs(Math.sin(Util.getMillis() / 180.0))
                    : 0.0F;

            // Well and yellow guards, like the recess on a real belly-box.
            disc(g, cx, cy, R + 1, 0xFF0A0B0C);
            disc(g, cx - 11, cy, 7, 0xFFF5CE1F);
            disc(g, cx + 11, cy, 7, 0xFFF5CE1F);
            disc(g, cx, cy, R - 1, 0xFF16181C);

            if (down) {
                int halo = lerpArgb(0x00D8232A, 0x90FF3A40, pulse);
                disc(g, cx, cy, R - 2, halo);
            }

            int skin = down
                    ? (alarm ? 0xFFFF2A32 : lerpArgb(STOP_RED_DARK, 0xFFC41C24, pulse))
                    : (isHovered ? 0xFFE8383E : STOP_RED);
            int sunk = down ? 2 : 0;
            disc(g, cx, cy + sunk, R - 4, 0xFF2A0A0C);
            disc(g, cx, cy + sunk, R - 5, skin);
            disc(g, cx, cy + sunk, R - 9, STOP_RED_DARK);
            disc(g, cx, cy + sunk, R - 11, skin);
            if (!down) {
                g.fill(cx - 5, cy - R + 6, cx + 3, cy - R + 8, 0x55FFFFFF);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
