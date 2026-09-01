package dev.nano.ndidisplays.client.computer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A computer's operating system — native Minecraft rendering, no browser engine anywhere.
 *
 * The whole desktop (wallpaper, icons, windows, taskbar, cursor) is drawn with the same
 * GuiGraphics calls as any Minecraft screen, into whatever framebuffer is bound when
 * {@link #render} runs. {@link Computers} points that at the machine's private render target, so
 * one drawing pass feeds three consumers at once: the in-world monitor, the sit-down screen, and
 * the NDI output.
 *
 * The OS works in a logical 640x360-ish coordinate space scaled up to the output resolution, so
 * the same desktop lays out identically at 854x480 and 1920x1080 — higher resolutions just get
 * crisper scaling, exactly like a real OS display scale.
 *
 * Every app is real: the notepad edits, the files persist (per computer, to disk), paint paints,
 * the image viewer fetches over HTTP, the music player plays the game's records through the
 * sound engine at the block, the NDI monitor shows any live source on the network, and the
 * terminal executes its commands.
 */
public final class ComputerOS {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Logical desktop height; width follows the output aspect. */
    public static final int LOGICAL_H = 360;

    static final int C_BAR = 0xFF14141C;
    static final int C_WIN = 0xFF1C1C26;
    static final int C_WIN2 = 0xFF23232F;
    static final int C_EDGE = 0xFF34344A;
    static final int C_TEXT = 0xFFE8E8F0;
    static final int C_DIM = 0xFF9AA0B0;
    static final int C_ACCENT = 0xFF4A9EFF;
    static final int C_RED = 0xFFC0392B;

    private static final int TASKBAR_H = 18;

    final String name;
    final BlockPos pos;
    final Minecraft mc = Minecraft.getInstance();
    final Font font;

    int wallpaper;
    boolean clock24 = true;

    /** The virtual drive: name + kind ("text"/"image") + data (content / URL). */
    final List<OsFile> files = new ArrayList<>();

    private final List<OsWindow> windows = new ArrayList<>();
    private int mouseX = 100;
    private int mouseY = 100;
    private boolean startOpen;
    private OsWindow dragging;
    private OsWindow resizing;
    private int dragDx;
    private int dragDy;
    private boolean dirty = true;

    int width = 640;
    int height = LOGICAL_H;

    record OsFile(String name, String kind, String data) {
    }

    public ComputerOS(String name, BlockPos pos) {
        this.name = name;
        this.pos = pos;
        this.font = mc.font;
        loadState();
    }

    // ================================================================ rendering

    /** Draws the whole desktop at the given logical size. Called with the OS target bound. */
    public void render(GuiGraphics g, int lw, int lh) {
        width = lw;
        height = lh;

        // wallpaper: a cheap vertical gradient in the chosen palette
        int[][] wp = {
                {0xFF0D1B2A, 0xFF232946}, {0xFF2D1B3D, 0xFF0F3460},
                {0xFF0F2027, 0xFF2C5364}, {0xFF101014, 0xFF16161C}};
        int[] c = wp[Math.floorMod(wallpaper, wp.length)];
        g.fillGradient(0, 0, lw, lh, c[0], c[1]);

        // desktop icons
        int ix = 8;
        int iy = 8;
        for (AppKind app : AppKind.values()) {
            g.fill(ix, iy, ix + 40, iy + 30, 0x30000000);
            g.drawCenteredString(font, app.glyph, ix + 20, iy + 4, app.colour);
            g.drawCenteredString(font, app.label, ix + 20, iy + 18, C_TEXT);
            iy += 38;
            if (iy > lh - TASKBAR_H - 40) {
                iy = 8;
                ix += 48;
            }
        }

        // windows, back to front
        for (OsWindow w : windows) {
            if (!w.minimized) {
                w.render(g, this);
            }
        }

        // taskbar
        int by = lh - TASKBAR_H;
        g.fill(0, by, lw, lh, C_BAR);
        g.fill(0, by, lw, by + 1, C_EDGE);
        g.fill(2, by + 2, 34, lh - 2, startOpen ? C_ACCENT : C_WIN2);
        g.drawString(font, "Start", 5, by + 5, C_TEXT, false);
        int tx = 40;
        for (OsWindow w : windows) {
            int tw = Math.min(60, font.width(w.app.title()) + 8);
            g.fill(tx, by + 2, tx + tw, lh - 2, w == focused() && !w.minimized ? C_ACCENT : C_WIN2);
            g.drawString(font, font.plainSubstrByWidth(w.app.title(), tw - 6), tx + 3, by + 5,
                    C_TEXT, false);
            w.taskX = tx;
            w.taskW = tw;
            tx += tw + 3;
        }
        LocalTime t = LocalTime.now();
        String clock = clock24
                ? String.format("%02d:%02d", t.getHour(), t.getMinute())
                : String.format("%d:%02d %s", t.getHour() % 12 == 0 ? 12 : t.getHour() % 12,
                        t.getMinute(), t.getHour() >= 12 ? "PM" : "AM");
        g.drawString(font, clock, lw - font.width(clock) - 4, by + 5, C_DIM, false);
        String nm = font.plainSubstrByWidth(name, 90);
        g.drawString(font, nm, lw - font.width(clock) - font.width(nm) - 14, by + 5,
                C_ACCENT, false);

        // start menu
        if (startOpen) {
            int mh = AppKind.values().length * 14 + 6;
            int my = by - mh - 2;
            g.fill(2, my, 92, by - 2, C_WIN);
            g.renderOutline(2, my, 90, mh, C_EDGE);
            int ey = my + 3;
            for (AppKind app : AppKind.values()) {
                g.drawString(font, app.glyph + " " + app.label, 7, ey + 2, C_TEXT, false);
                ey += 14;
            }
        }

        // cursor: the pointer belongs on the NDI output too
        g.fill(mouseX, mouseY, mouseX + 1, mouseY + 7, 0xFFFFFFFF);
        g.fill(mouseX, mouseY, mouseX + 5, mouseY + 1, 0xFFFFFFFF);
        g.fill(mouseX + 1, mouseY + 1, mouseX + 4, mouseY + 4, 0xFF000000);
    }

    // ================================================================ input (logical coords)

    public void mouseMove(int x, int y) {
        mouseX = clamp(x, 0, width - 1);
        mouseY = clamp(y, 0, height - 1);
        if (dragging != null) {
            dragging.x = clamp(mouseX - dragDx, -dragging.w + 30, width - 20);
            dragging.y = clamp(mouseY - dragDy, 0, height - TASKBAR_H - 10);
        }
        if (resizing != null) {
            resizing.w = clamp(mouseX - resizing.x + dragDx, 120, width);
            resizing.h = clamp(mouseY - resizing.y + dragDy, 60, height - TASKBAR_H);
        }
        OsWindow f = focused();
        if (f != null && !f.minimized) {
            f.app.mouseMove(mouseX - f.x - 2, mouseY - f.y - 14);
        }
    }

    public void mouseDown(int x, int y, int button) {
        mouseMove(x, y);
        int by = height - TASKBAR_H;

        // start menu
        if (startOpen) {
            int mh = AppKind.values().length * 14 + 6;
            int my = by - mh - 2;
            if (x >= 2 && x < 92 && y >= my && y < by - 2) {
                int idx = (y - my - 3) / 14;
                AppKind[] apps = AppKind.values();
                if (idx >= 0 && idx < apps.length) {
                    open(apps[idx]);
                }
                startOpen = false;
                return;
            }
            startOpen = false;
        }
        if (y >= by) {
            if (x >= 2 && x < 34) {
                startOpen = !startOpen;
                return;
            }
            for (OsWindow w : windows) {
                if (x >= w.taskX && x < w.taskX + w.taskW) {
                    if (w.minimized || w != focused()) {
                        w.minimized = false;
                        focus(w);
                    } else {
                        w.minimized = true;
                    }
                    return;
                }
            }
            return;
        }

        // windows, front to back
        for (int i = windows.size() - 1; i >= 0; i--) {
            OsWindow w = windows.get(i);
            if (w.minimized || !w.contains(x, y)) {
                continue;
            }
            focus(w);
            if (x >= w.x + w.w - 8 && y >= w.y + w.h - 8) { // resize grip
                resizing = w;
                dragDx = w.x + w.w - x;
                dragDy = w.y + w.h - y;
            } else if (y < w.y + 14) { // titlebar
                if (x >= w.x + w.w - 12) {
                    close(w);
                } else if (x >= w.x + w.w - 24) {
                    w.minimized = true;
                } else {
                    dragging = w;
                    dragDx = x - w.x;
                    dragDy = y - w.y;
                }
            } else {
                w.app.mouseDown(x - w.x - 2, y - w.y - 14, button);
            }
            return;
        }

        // desktop icons (single click opens; this is a friendly OS)
        int ix = 8;
        int iy = 8;
        for (AppKind app : AppKind.values()) {
            if (x >= ix && x < ix + 40 && y >= iy && y < iy + 30) {
                open(app);
                return;
            }
            iy += 38;
            if (iy > height - TASKBAR_H - 40) {
                iy = 8;
                ix += 48;
            }
        }
    }

    public void mouseUp(int x, int y, int button) {
        mouseMove(x, y);
        dragging = null;
        resizing = null;
        OsWindow f = focused();
        if (f != null && !f.minimized) {
            f.app.mouseUp(x - f.x - 2, y - f.y - 14, button);
        }
    }

    public void scroll(double amount) {
        OsWindow f = focused();
        if (f != null && !f.minimized) {
            f.app.scroll(amount);
        }
    }

    public void keyDown(int key, int mods) {
        OsWindow f = focused();
        if (f != null && !f.minimized) {
            f.app.keyDown(key, mods);
        }
    }

    public void charTyped(char c) {
        OsWindow f = focused();
        if (f != null && !f.minimized) {
            f.app.charTyped(c);
        }
    }

    // ================================================================ window management

    private OsWindow focused() {
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    private void focus(OsWindow w) {
        windows.remove(w);
        windows.add(w);
    }

    void open(AppKind kind) {
        for (OsWindow w : windows) {
            if (w.kind == kind) {
                w.minimized = false;
                focus(w);
                return;
            }
        }
        OsApp app = switch (kind) {
            case BROWSER -> new BrowserApp(this);
            case NOTEPAD -> new NotepadApp(this);
            case FILES -> new FilesApp(this);
            case PAINT -> new PaintApp(this);
            case IMAGES -> new ImageViewerApp(this);
            case MUSIC -> new MusicApp(this);
            case NDI -> new NdiMonitorApp(this);
            case TERMINAL -> new TerminalApp(this);
            case SETTINGS -> new SettingsApp(this);
        };
        OsWindow w = new OsWindow(kind, app);
        w.w = app.preferredW();
        w.h = app.preferredH();
        int n = windows.size();
        w.x = Math.min(56 + n * 16, Math.max(4, width - w.w - 8));
        w.y = Math.min(18 + n * 12, Math.max(2, height - TASKBAR_H - w.h - 4));
        windows.add(w);
    }

    private void close(OsWindow w) {
        w.app.onClose();
        windows.remove(w);
    }

    /** Block removed / world left: release textures and sounds, save the drive. */
    public void shutdown() {
        stopDisc();
        for (OsWindow w : new ArrayList<>(windows)) {
            close(w);
        }
        saveState();
    }

    // ================================================================ shared services for apps

    void saveFile(String fname, String kind, String data) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).name().equals(fname)) {
                files.set(i, new OsFile(fname, kind, data));
                saveState();
                return;
            }
        }
        files.add(new OsFile(fname, kind, data));
        saveState();
    }

    void openTextFile(String fname, String content) {
        open(AppKind.NOTEPAD);
        for (OsWindow w : windows) {
            if (w.kind == AppKind.NOTEPAD) {
                ((NotepadApp) w.app).load(fname, content);
            }
        }
    }

    void openImageFile(String url) {
        open(AppKind.IMAGES);
        for (OsWindow w : windows) {
            if (w.kind == AppKind.IMAGES) {
                ((ImageViewerApp) w.app).show(url);
            }
        }
    }

    /** Blocks drawn straight from a raw GL texture id (NDI streams, paint canvases). */
    static void rawTexture(GuiGraphics g, int texId, int x, int y, int w, int h) {
        if (texId == 0) {
            return;
        }
        g.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.disableBlend();
        Matrix4f mat = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        b.vertex(mat, x, y + h, 0).uv(0.0F, 1.0F).endVertex();
        b.vertex(mat, x + w, y + h, 0).uv(1.0F, 1.0F).endVertex();
        b.vertex(mat, x + w, y, 0).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, x, y, 0).uv(0.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
    }

    /** Async HTTP fetch of an image, delivered on the render thread as a texture id. */
    void fetchImage(String url, java.util.function.BiConsumer<Integer, String> done) {
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            try {
                byte[] bytes;
                if (url.startsWith("file:")) {
                    bytes = Files.readAllBytes(Path.of(java.net.URI.create(url)));
                } else {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "NDI-Displays-Computer");
                    try (InputStream in = conn.getInputStream()) {
                        bytes = in.readAllBytes();
                    }
                }
                NativeImage img = NativeImage.read(new java.io.ByteArrayInputStream(bytes));
                mc.execute(() -> {
                    DynamicTexture tex = new DynamicTexture(img);
                    ResourceLocation loc = mc.getTextureManager().register("computer_img", tex);
                    done.accept(tex.getId(), img.getWidth() + "x" + img.getHeight() + "  " + loc);
                });
            } catch (Exception e) {
                mc.execute(() -> done.accept(0, "failed: " + e.getMessage()));
            }
        });
    }

    /** The game's records, played positionally at the computer — the block IS the speaker. */
    static final Object[][] DISCS = {
            {"C418 — 13", SoundEvents.MUSIC_DISC_13}, {"C418 — Cat", SoundEvents.MUSIC_DISC_CAT},
            {"C418 — Blocks", SoundEvents.MUSIC_DISC_BLOCKS},
            {"C418 — Chirp", SoundEvents.MUSIC_DISC_CHIRP},
            {"C418 — Far", SoundEvents.MUSIC_DISC_FAR}, {"C418 — Mall", SoundEvents.MUSIC_DISC_MALL},
            {"C418 — Mellohi", SoundEvents.MUSIC_DISC_MELLOHI},
            {"C418 — Stal", SoundEvents.MUSIC_DISC_STAL},
            {"C418 — Strad", SoundEvents.MUSIC_DISC_STRAD},
            {"C418 — Ward", SoundEvents.MUSIC_DISC_WARD}, {"C418 — 11", SoundEvents.MUSIC_DISC_11},
            {"C418 — Wait", SoundEvents.MUSIC_DISC_WAIT},
            {"Lena Raine — Otherside", SoundEvents.MUSIC_DISC_OTHERSIDE},
            {"Lena Raine — Pigstep", SoundEvents.MUSIC_DISC_PIGSTEP},
            {"Aaron Cherof — Relic", SoundEvents.MUSIC_DISC_RELIC},
    };

    SimpleSoundInstance nowPlaying;
    int nowPlayingIdx = -1;

    void playDisc(int idx) {
        stopDisc();
        SoundEvent ev = (SoundEvent) DISCS[idx][1];
        nowPlaying = SimpleSoundInstance.forRecord(ev, Vec3.atCenterOf(pos));
        nowPlayingIdx = idx;
        mc.getSoundManager().play(nowPlaying);
    }

    void stopDisc() {
        if (nowPlaying != null) {
            mc.getSoundManager().stop(nowPlaying);
            nowPlaying = null;
            nowPlayingIdx = -1;
        }
    }

    boolean discPlaying() {
        return nowPlaying != null && mc.getSoundManager().isActive(nowPlaying);
    }

    // ================================================================ persistence

    private Path stateFile() {
        String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        return mc.gameDirectory.toPath().resolve("ndidisplays").resolve("computers")
                .resolve(safe + ".json");
    }

    void saveState() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("wallpaper", wallpaper);
            root.addProperty("clock24", clock24);
            JsonArray arr = new JsonArray();
            for (OsFile f : files) {
                JsonObject o = new JsonObject();
                o.addProperty("name", f.name());
                o.addProperty("kind", f.kind());
                o.addProperty("data", f.data());
                arr.add(o);
            }
            root.add("files", arr);
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[ndidisplays] computer '{}' could not save its drive: {}", name,
                    e.toString());
        }
    }

    private void loadState() {
        try {
            Path file = stateFile();
            if (!Files.exists(file)) {
                files.add(new OsFile("welcome.txt", "text",
                        "Welcome to " + name + "!\n\nThis machine is a live NDI source —"
                        + " whatever is on this screen is on the network.\n\nOpen Notepad and"
                        + " save files here; Paint and Images can fill the drive too."));
                return;
            }
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            wallpaper = root.has("wallpaper") ? root.get("wallpaper").getAsInt() : 0;
            clock24 = !root.has("clock24") || root.get("clock24").getAsBoolean();
            if (root.has("files")) {
                for (var el : root.getAsJsonArray("files")) {
                    JsonObject o = el.getAsJsonObject();
                    files.add(new OsFile(o.get("name").getAsString(), o.get("kind").getAsString(),
                            o.get("data").getAsString()));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[ndidisplays] computer '{}' could not load its drive: {}", name,
                    e.toString());
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ================================================================ window shell

    enum AppKind {
        BROWSER("[@]", "Browser", 0xFF60A0FF),
        NOTEPAD("[=]", "Notes", 0xFFF0D060),
        FILES("[/]", "Files", 0xFF60C0F0),
        PAINT("[~]", "Paint", 0xFFF080B0),
        IMAGES("[#]", "Images", 0xFF80E0A0),
        MUSIC("[>]", "Music", 0xFFB090F0),
        NDI("[o]", "NDI Mon", 0xFF70E0E0),
        TERMINAL("[$]", "Term", 0xFF70F070),
        SETTINGS("[*]", "Settings", 0xFFC0C0D0);

        final String glyph;
        final String label;
        final int colour;

        AppKind(String glyph, String label, int colour) {
            this.glyph = glyph;
            this.label = label;
            this.colour = colour;
        }
    }

    static final class OsWindow {
        final AppKind kind;
        final OsApp app;
        int x;
        int y;
        int w;
        int h;
        boolean minimized;
        int taskX;
        int taskW;

        OsWindow(AppKind kind, OsApp app) {
            this.kind = kind;
            this.app = app;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        void render(GuiGraphics g, ComputerOS os) {
            g.fill(x, y, x + w, y + h, C_WIN);
            g.renderOutline(x, y, w, h, C_EDGE);
            g.fill(x + 1, y + 1, x + w - 1, y + 13, C_WIN2);
            g.drawString(os.font, os.font.plainSubstrByWidth(app.title(), w - 34),
                    x + 4, y + 3, C_TEXT, false);
            g.drawString(os.font, "-", x + w - 21, y + 3, C_DIM, false);
            g.drawString(os.font, "x", x + w - 10, y + 3, C_RED, false);
            // resize grip in the corner
            g.fill(x + w - 6, y + h - 2, x + w - 2, y + h - 1, C_DIM);
            g.fill(x + w - 2, y + h - 6, x + w - 1, y + h - 2, C_DIM);
            // No scissor: GuiGraphics scissoring assumes the real window's GUI scale, which is
            // meaningless inside the computer's own framebuffer. Apps stay inside their bounds.
            g.pose().pushPose();
            g.pose().translate(x + 2, y + 14, 0);
            app.render(g, w - 4, h - 16);
            g.pose().popPose();
        }
    }
}
