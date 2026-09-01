package dev.nano.ndidisplays.client.computer;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paint: a pixel canvas backed by a real texture. Strokes write into a NativeImage and re-upload,
 * so drawing costs almost nothing per frame no matter the canvas size, and Save writes an actual
 * PNG to disk and files it on the drive, where Images can open it again.
 */
class PaintApp extends OsApp {

    private static final int CW = 192;
    private static final int CH = 108;
    private static final int[] PALETTE = {
            0xFFFFFFFF, 0xFF101014, 0xFFC0392B, 0xFFE67E22, 0xFFF1C40F,
            0xFF2ECC71, 0xFF3498DB, 0xFF9B59B6, 0xFFE84393, 0xFF95A5A6};

    private final DynamicTexture canvas;
    private final ResourceLocation loc;
    private int colour = 0xFFFFFFFF;
    private int brush = 2;
    private boolean drawing;
    private boolean changed;
    private String status = "";
    private long statusUntil;

    PaintApp(ComputerOS os) {
        super(os);
        NativeImage img = new NativeImage(CW, CH, false);
        img.fillRect(0, 0, CW, CH, 0xFF141418); // ABGR fill; dark near-black canvas
        canvas = new DynamicTexture(img);
        loc = os.mc.getTextureManager().register("computer_paint", canvas);
    }

    @Override
    String title() {
        return "Paint";
    }

    @Override
    int preferredW() {
        return CW + 8;
    }

    @Override
    int preferredH() {
        return CH + 34;
    }

    @Override
    void render(GuiGraphics g, int w, int h) {
        // palette + brush + save
        for (int i = 0; i < PALETTE.length; i++) {
            int px = 2 + i * 13;
            g.fill(px, 2, px + 11, 13, PALETTE[i]);
            if (PALETTE[i] == colour) {
                g.renderOutline(px - 1, 1, 13, 13, 0xFFFFFFFF);
            }
        }
        int bx = 2 + PALETTE.length * 13 + 6;
        g.fill(bx, 2, bx + 14, 13, ComputerOS.C_WIN2);
        g.drawString(os.font, "B" + brush, bx + 2, 4, ComputerOS.C_TEXT, false);
        g.fill(bx + 18, 2, bx + 46, 13, ComputerOS.C_ACCENT);
        g.drawString(os.font, "Save", bx + 21, 4, 0xFFFFFFFF, false);
        if (System.currentTimeMillis() < statusUntil) {
            g.drawString(os.font, status, bx + 50, 4, ComputerOS.C_DIM, false);
        }

        if (changed) {
            canvas.upload();
            changed = false;
        }
        g.blit(loc, 2, 16, 0, 0.0F, 0.0F, CW, CH, CW, CH);
        g.renderOutline(1, 15, CW + 2, CH + 2, ComputerOS.C_EDGE);
    }

    private void paint(int x, int y) {
        int cx = x - 2;
        int cy = y - 16;
        NativeImage img = canvas.getPixels();
        if (img == null) {
            return;
        }
        // NativeImage wants ABGR; palette entries are ARGB.
        int abgr = (colour & 0xFF00FF00) | ((colour & 0xFF) << 16) | ((colour >> 16) & 0xFF);
        for (int dx = -brush + 1; dx < brush; dx++) {
            for (int dy = -brush + 1; dy < brush; dy++) {
                int px = cx + dx;
                int py = cy + dy;
                if (px >= 0 && px < CW && py >= 0 && py < CH) {
                    img.setPixelRGBA(px, py, abgr);
                }
            }
        }
        changed = true;
    }

    @Override
    void mouseDown(int x, int y, int button) {
        if (y < 15) {
            for (int i = 0; i < PALETTE.length; i++) {
                int px = 2 + i * 13;
                if (x >= px && x < px + 11) {
                    colour = PALETTE[i];
                    return;
                }
            }
            int bx = 2 + PALETTE.length * 13 + 6;
            if (x >= bx && x < bx + 14) {
                brush = brush % 4 + 1;
                return;
            }
            if (x >= bx + 18 && x < bx + 46) {
                save();
                return;
            }
            return;
        }
        drawing = true;
        paint(x, y);
    }

    @Override
    void mouseMove(int x, int y) {
        if (drawing && y >= 15) {
            paint(x, y);
        }
    }

    @Override
    void mouseUp(int x, int y, int button) {
        drawing = false;
    }

    private void save() {
        try {
            Path dir = os.mc.gameDirectory.toPath().resolve("ndidisplays").resolve("computers");
            Files.createDirectories(dir);
            String fname = "painting_" + (System.currentTimeMillis() % 100000) + ".png";
            Path out = dir.resolve(fname);
            NativeImage img = canvas.getPixels();
            if (img != null) {
                img.writeToFile(out);
                os.saveFile(fname, "image", out.toUri().toString());
                status = "saved ✓";
            }
        } catch (Exception e) {
            status = "save failed";
        }
        statusUntil = System.currentTimeMillis() + 1800;
    }

    @Override
    void onClose() {
        os.mc.getTextureManager().release(loc);
    }
}
