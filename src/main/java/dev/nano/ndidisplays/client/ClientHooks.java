package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.block.WallScanner;
import dev.nano.ndidisplays.client.gui.CameraConfigScreen;
import dev.nano.ndidisplays.client.gui.PanelConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Client-only entry points, kept out of common classes for dedicated-server safety. */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void openPanelConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || !(level.getBlockEntity(pos) instanceof LedPanelBlockEntity clicked)) {
            return;
        }
        dev.nano.ndidisplays.block.PanelFacing facing = clicked.getFacing();
        BlockPos anchorPos = WallScanner.findAnchor(level, pos, facing, clicked.getPanelKind());
        if (!(level.getBlockEntity(anchorPos) instanceof LedPanelBlockEntity anchor)) {
            return;
        }
        mc.setScreen(new PanelConfigScreen(anchor));
    }

    public static void openCameraConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos) instanceof NdiCameraBlockEntity camera) {
            mc.setScreen(new CameraConfigScreen(camera));
        }
    }

    public static void openRouterConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.NdiRouterBlockEntity router) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.RouterConfigScreen(router));
        }
    }
}
