package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.block.WallScanner;
import dev.nano.ndidisplays.client.gui.CameraConfigScreen;
import dev.nano.ndidisplays.client.gui.PanelConfigScreen;
import dev.nano.ndidisplays.entity.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Client-only entry points, kept out of common classes for dedicated-server safety. */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void openFloorConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || !(level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.LedFloorBlockEntity clicked)) {
            return;
        }
        net.minecraft.core.Direction facing = clicked.getFacing();
        BlockPos anchorPos = dev.nano.ndidisplays.block.FloorScanner.findAnchor(
                level, pos, facing, clicked.getPanelKind());
        if (!(level.getBlockEntity(anchorPos)
                instanceof dev.nano.ndidisplays.block.LedFloorBlockEntity anchor)) {
            return;
        }
        mc.setScreen(new dev.nano.ndidisplays.client.gui.FloorConfigScreen(anchor));
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

    public static void openWinchConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.KineticWinchBlockEntity winch) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.WinchConfigScreen(winch));
        }
    }

    public static void openNdiCardConfig(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        net.minecraft.world.item.ItemStack stack = mc.player.getItemInHand(hand);
        if (stack.getItem() instanceof dev.nano.ndidisplays.item.NdiConfigCardItem) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.NdiCardScreen(hand,
                    dev.nano.ndidisplays.item.NdiConfigCardItem.storedSource(stack)));
        }
    }

    public static void openProjectorConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.ProjectorBlockEntity projector) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.ProjectorConfigScreen(projector));
        }
    }

    public static void openRoundScreenConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.RoundScreenBlockEntity screen) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.RoundScreenConfigScreen(screen));
        }
    }

    public static void openCurvedScreenConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.CurvedScreenBlockEntity screen) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.CurvedScreenConfigScreen(screen));
        }
    }

    public static void openMultiviewConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.MultiviewBlockEntity monitor) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.MultiviewConfigScreen(monitor));
        }
    }

    public static void openWinchParkMonitor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.WinchParkMonitorBlockEntity monitor
                && monitor.isBoundIn(level)) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.WinchParkScreen(null,
                    monitor.getParkPos1(), monitor.getParkPos2()));
        }
    }

    /**
     * Opens the worn shoulder rig's aim controls. Prefers the worn stack over a held one, since
     * aiming it while wearing it is the normal case.
     */
    public static void openShoulderRigConfig() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        net.minecraft.world.item.ItemStack rig =
                mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        if (!rig.is(dev.nano.ndidisplays.NdiDisplays.SHOULDER_CAMERA_ITEM.get())) {
            rig = mc.player.getMainHandItem()
                    .is(dev.nano.ndidisplays.NdiDisplays.SHOULDER_CAMERA_ITEM.get())
                    ? mc.player.getMainHandItem()
                    : mc.player.getOffhandItem();
        }
        if (rig.is(dev.nano.ndidisplays.NdiDisplays.SHOULDER_CAMERA_ITEM.get())) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.ShoulderRigScreen(rig));
        }
    }

    /** Opens a web terminal: the page, a URL bar, and mouse/keyboard wired to the browser. */
    public static void openRackRouter(BlockPos pos, int slot) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.RackBlockEntity rack) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.RackRouterScreen(rack, slot));
        }
    }

    public static void openRackWeb(BlockPos pos, int slot) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.RackBlockEntity rack) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.RackWebScreen(rack, slot));
        }
    }

    public static void openSwitcher(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.SwitcherBlockEntity sw) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.SwitcherScreen(sw));
        }
    }

    public static void openProMonitor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.ProMonitorBlockEntity mon) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.ProMonitorScreen(mon));
        }
    }

    public static void openComputer(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos)
                instanceof dev.nano.ndidisplays.block.ComputerBlockEntity pc) {
            mc.setScreen(new dev.nano.ndidisplays.client.gui.ComputerScreen(pc));
        }
    }

    public static void openWebTerminal(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null
                || !(level.getBlockEntity(pos)
                        instanceof dev.nano.ndidisplays.block.WebTerminalBlockEntity terminal)) {
            return;
        }
        mc.setScreen(new dev.nano.ndidisplays.client.gui.WebTerminalScreen(terminal));
    }

    public static void openCameraConfig(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && level.getBlockEntity(pos) instanceof NdiCameraBlockEntity camera) {
            mc.setScreen(new CameraConfigScreen(camera));
        }
    }

    public static void openDroneConfig(java.util.UUID droneId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        DroneEntity drone = findDrone(mc.level, droneId);
        if (drone == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "gui.ndidisplays.drone.no_signal"), true);
            }
            return;
        }
        mc.setScreen(new dev.nano.ndidisplays.client.gui.DroneConfigScreen(drone));
    }

    public static DroneEntity findDrone(Level level, java.util.UUID id) {
        if (id == null || !(level instanceof net.minecraft.client.multiplayer.ClientLevel client)) {
            return null;
        }
        for (net.minecraft.world.entity.Entity entity : client.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone && drone.getUUID().equals(id)) {
                return drone;
            }
        }
        return null;
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
