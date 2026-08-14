package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.winch.WinchParkLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Desk plot of a kinetic park: live drop wells on a dark console, coloured by
 * payload, with a status strip on hover.
 */
public class WinchParkScreen extends Screen {

    private static final int COL_BG = 0xE8080A0C;
    private static final int COL_PANEL = 0xF2101418;
    private static final int COL_LINE = 0xFF2A3A40;
    private static final int COL_WELL = 0xFF07090B;
    private static final int COL_MUTED = 0xFF7A8A90;
    private static final int COL_TITLE = 0xFFC8E8E2;

    @Nullable
    private final Screen parent;
    private final BlockPos pos1;
    private final BlockPos pos2;

    @Nullable
    private WinchParkLayout.Motor hovered;

    public WinchParkScreen(@Nullable Screen parent, BlockPos pos1, BlockPos pos2) {
        super(Component.translatable("gui.ndidisplays.park.title"));
        this.parent = parent;
        this.pos1 = pos1;
        this.pos2 = pos2;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width - 80, 5, 68, 18).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, COL_BG);

        graphics.fill(0, 0, width, 28, COL_PANEL);
        graphics.fill(0, 27, width, 28, COL_LINE);
        graphics.fill(10, 10, 18, 18, 0xFF34E0B8);
        graphics.drawString(font, "PARK PLOT", 26, 10, COL_TITLE, false);

        Level level = Minecraft.getInstance().level;
        List<WinchParkLayout.Motor> motors = WinchParkLayout.layout(
                WinchParkLayout.collect(level, pos1, pos2));
        if (motors.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.ndidisplays.park.empty"),
                    width / 2, height / 2, COL_MUTED);
            drawLegend(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int cols = motors.get(0).cols();
        int rows = motors.get(0).rows();
        String header = motors.size() + "  ·  " + cols + "\u00D7" + rows;
        graphics.drawString(font, header, width - 100 - font.width(header), 10, COL_MUTED, false);

        int maxW = width - 36;
        int maxH = height - 92;
        int gap = cols * rows > 120 ? 2 : 3;
        int cell = Math.min(34, Math.max(12, Math.min(maxW / cols, maxH / rows) - gap));
        int gridW = cols * (cell + gap) - gap;
        int gridH = rows * (cell + gap) - gap;
        int ox = (width - gridW) / 2;
        int oy = 40 + Math.max(0, (maxH - gridH) / 2);

        graphics.fill(ox - 8, oy - 8, ox + gridW + 8, oy + gridH + 8, COL_PANEL);
        graphics.fill(ox - 8, oy - 8, ox + gridW + 8, oy - 7, COL_LINE);
        graphics.fill(ox - 8, oy + gridH + 7, ox + gridW + 8, oy + gridH + 8, COL_LINE);
        graphics.fill(ox - 8, oy - 8, ox - 7, oy + gridH + 8, COL_LINE);
        graphics.fill(ox + gridW + 7, oy - 8, ox + gridW + 8, oy + gridH + 8, COL_LINE);

        hovered = null;
        float pt = Minecraft.getInstance().getFrameTime();
        for (WinchParkLayout.Motor motor : motors) {
            int x = ox + motor.gridX() * (cell + gap);
            int y = oy + motor.gridZ() * (cell + gap);
            boolean over = mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell;
            if (over) {
                hovered = motor;
            }
            drawMotor(graphics, motor, x, y, cell, pt, over);
        }

        drawLegend(graphics);
        if (hovered != null) {
            KineticWinchBlockEntity be = hovered.be();
            String line = String.format("%s   U%d.%03d   %.2f m   %s",
                    payloadName(be.getPayload()),
                    be.getDmxUniverse(), be.getDmxAddress(),
                    be.getRenderDrop(pt),
                    be.getSourceName().isEmpty() ? "—" : be.getSourceName());
            graphics.fill(0, height - 32, width - 96, height, COL_PANEL);
            graphics.fill(0, height - 32, width - 96, height - 31, COL_LINE);
            graphics.drawString(font, line, 12, height - 22, COL_TITLE, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLegend(GuiGraphics graphics) {
        int x = 12;
        int y = height - 50;
        x = chip(graphics, x, y, payloadColor(KineticWinchBlockEntity.PAYLOAD_LED_TILE), "Tile");
        x = chip(graphics, x, y, payloadColor(KineticWinchBlockEntity.PAYLOAD_SLAT), "Slat");
        x = chip(graphics, x, y, payloadColor(KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE), "Sphere");
        x = chip(graphics, x, y, payloadColor(KineticWinchBlockEntity.PAYLOAD_MIRROR_BALL), "Mirror");
        chip(graphics, x, y, payloadColor(KineticWinchBlockEntity.PAYLOAD_FIXTURE), "Fixture");
    }

    private int chip(GuiGraphics graphics, int x, int y, int color, String label) {
        graphics.fill(x, y + 2, x + 7, y + 9, color);
        graphics.drawString(font, label, x + 10, y + 1, COL_MUTED, false);
        return x + 14 + font.width(label) + 10;
    }

    private void drawMotor(GuiGraphics graphics, WinchParkLayout.Motor motor,
                           int x, int y, int cell, float pt, boolean highlight) {
        KineticWinchBlockEntity be = motor.be();
        int color = payloadColor(be.getPayload());
        graphics.fill(x, y, x + cell, y + cell, highlight ? 0xFF1A2428 : 0xFF101418);
        graphics.fill(x, y, x + cell, y + 1, highlight ? 0xFF34E0B8 : 0xFF243038);
        graphics.fill(x, y + cell - 1, x + cell, y + cell, highlight ? 0xFF34E0B8 : 0xFF243038);
        graphics.fill(x, y, x + 1, y + cell, highlight ? 0xFF34E0B8 : 0xFF243038);
        graphics.fill(x + cell - 1, y, x + cell, y + cell, highlight ? 0xFF34E0B8 : 0xFF243038);

        int pad = Math.max(2, cell / 8);
        int wellX = x + pad;
        int wellY = y + pad;
        int wellW = cell - pad * 2;
        int wellH = cell - pad * 2 - (cell >= 18 ? 8 : 0);
        graphics.fill(wellX, wellY, wellX + wellW, wellY + wellH, COL_WELL);

        float span = Math.max(0.01F, be.getMaxDrop() - be.getMinDrop());
        float nA = clamp01((be.getRenderDrop(pt) - be.getMinDrop()) / span);
        int dark = darken(color, 0.45F);
        if (be.isTwinMode() && be.getPayload() == KineticWinchBlockEntity.PAYLOAD_LED_TILE) {
            float nB = clamp01((be.getRenderDropB(pt) - be.getMinDrop()) / span);
            int split = Math.max(1, wellW / 10);
            int barW = (wellW - split) / 2;
            fillBar(graphics, wellX, wellY, barW, wellH, nA, color, dark);
            fillBar(graphics, wellX + barW + split, wellY, wellW - barW - split, wellH, nB, color, dark);
        } else {
            fillBar(graphics, wellX, wellY, wellW, wellH, nA, color, dark);
        }

        if (cell >= 18) {
            String addr = String.valueOf(be.getDmxAddress());
            graphics.drawString(font, addr, x + (cell - font.width(addr)) / 2, y + cell - 9,
                    highlight ? COL_TITLE : COL_MUTED, false);
        }
    }

    private static void fillBar(GuiGraphics graphics, int x, int y, int w, int h,
                                float n, int bright, int dark) {
        int fill = Math.max(2, Math.round(n * h));
        int top = y + h - fill;
        graphics.fillGradient(x, top, x + w, y + h, bright, dark);
        graphics.fill(x, top, x + w, top + Math.max(1, fill / 6), bright);
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static int darken(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >>> 16) & 0xFF) * factor);
        int g = (int) (((argb >>> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int payloadColor(int payload) {
        return switch (payload) {
            case KineticWinchBlockEntity.PAYLOAD_SLAT -> 0xFF47EB94;
            case KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE -> 0xFFF257B8;
            case KineticWinchBlockEntity.PAYLOAD_MIRROR_BALL -> 0xFFD1DBE6;
            case KineticWinchBlockEntity.PAYLOAD_FIXTURE -> 0xFFFAA338;
            default -> 0xFF38C7EB;
        };
    }

    private static String payloadName(int payload) {
        return switch (payload) {
            case KineticWinchBlockEntity.PAYLOAD_SLAT -> "Slat";
            case KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE -> "Sphere";
            case KineticWinchBlockEntity.PAYLOAD_MIRROR_BALL -> "Mirror";
            case KineticWinchBlockEntity.PAYLOAD_FIXTURE -> "Fixture";
            default -> "Tile";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
