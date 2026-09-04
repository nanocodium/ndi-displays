package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {

    /** Beyond normal reach, but generous enough for configuring a wall you are standing back from. */
    private static final double MAX_CONFIG_DISTANCE_SQR = 64.0 * 64.0;

    /** A radio remote reaches across a venue; the chunk sweep bounds it in practice. */
    private static final double MAX_REMOTE_DISTANCE_SQR = 192.0 * 192.0;

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NdiDisplays.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private NetworkHandler() {
    }

    /**
     * Whether this player is allowed to reconfigure the block at {@code pos}.
     *
     * These packets carry a position chosen by the client, so the server must not take the
     * client's word for any of it. Without these checks a spectator, an adventure-mode
     * player, or anyone with a modified client could retune walls and force cameras live
     * anywhere in the world — including inside spawn protection or another player's claim.
     */
    /** Same rules as a block config, but the drone may already be far above the operator. */
    public static boolean mayConfigureEntity(ServerPlayer player, net.minecraft.world.entity.Entity entity) {
        if (player.isSpectator() || !player.getAbilities().mayBuild) {
            return false;
        }
        if (player.getVehicle() == entity) {
            return true;
        }
        return player.distanceToSqr(entity) <= MAX_CONFIG_DISTANCE_SQR;
    }

    /**
     * Whether this player may run the motor at {@code pos} from a radio remote.
     *
     * Same permission rules as a pendant, but with the reach of an actual radio: a rigger
     * runs a roof from the floor, and a motor two hundred blocks up is the normal case, not
     * an exploit. The build and claim checks are what keep it honest.
     */
    public static boolean mayOperateRemote(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator() || !player.getAbilities().mayBuild) {
            return false;
        }
        if (!player.level().isLoaded(pos)) {
            return false;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_REMOTE_DISTANCE_SQR) {
            return false;
        }
        return player.level().mayInteract(player, pos);
    }

    public static boolean mayConfigure(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator() || !player.getAbilities().mayBuild) {
            return false;
        }
        if (!player.level().isLoaded(pos)) {
            return false;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_CONFIG_DISTANCE_SQR) {
            return false;
        }
        // Honours spawn protection and anything else hooking block interaction (claims, etc).
        return player.level().mayInteract(player, pos);
    }

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(id++, UpdateWallConfigPacket.class,
                UpdateWallConfigPacket::encode,
                UpdateWallConfigPacket::decode,
                UpdateWallConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCameraConfigPacket.class,
                UpdateCameraConfigPacket::encode,
                UpdateCameraConfigPacket::decode,
                UpdateCameraConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateRouterConfigPacket.class,
                UpdateRouterConfigPacket::encode,
                UpdateRouterConfigPacket::decode,
                UpdateRouterConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateShoulderRigPacket.class,
                UpdateShoulderRigPacket::encode,
                UpdateShoulderRigPacket::decode,
                UpdateShoulderRigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateWebTerminalPacket.class,
                UpdateWebTerminalPacket::encode,
                UpdateWebTerminalPacket::decode,
                UpdateWebTerminalPacket::handle);
        CHANNEL.registerMessage(id++, UpdateWinchConfigPacket.class,
                UpdateWinchConfigPacket::encode,
                UpdateWinchConfigPacket::decode,
                UpdateWinchConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateNdiCardPacket.class,
                UpdateNdiCardPacket::encode,
                UpdateNdiCardPacket::decode,
                UpdateNdiCardPacket::handle);
        CHANNEL.registerMessage(id++, ApplyNdiCardRegionPacket.class,
                ApplyNdiCardRegionPacket::encode,
                ApplyNdiCardRegionPacket::decode,
                ApplyNdiCardRegionPacket::handle);
        CHANNEL.registerMessage(id++, UpdateRoundScreenConfigPacket.class,
                UpdateRoundScreenConfigPacket::encode,
                UpdateRoundScreenConfigPacket::decode,
                UpdateRoundScreenConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCurvedScreenConfigPacket.class,
                UpdateCurvedScreenConfigPacket::encode,
                UpdateCurvedScreenConfigPacket::decode,
                UpdateCurvedScreenConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateScreenDmxSlotsPacket.class,
                UpdateScreenDmxSlotsPacket::encode,
                UpdateScreenDmxSlotsPacket::decode,
                UpdateScreenDmxSlotsPacket::handle);
        CHANNEL.registerMessage(id++, UpdateScreenCropPacket.class,
                UpdateScreenCropPacket::encode,
                UpdateScreenCropPacket::decode,
                UpdateScreenCropPacket::handle);
        CHANNEL.registerMessage(id++, UpdateFloorConfigPacket.class,
                UpdateFloorConfigPacket::encode,
                UpdateFloorConfigPacket::decode,
                UpdateFloorConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateMultiviewConfigPacket.class,
                UpdateMultiviewConfigPacket::encode,
                UpdateMultiviewConfigPacket::decode,
                UpdateMultiviewConfigPacket::handle);
        CHANNEL.registerMessage(id++, BindParkMonitorPacket.class,
                BindParkMonitorPacket::encode,
                BindParkMonitorPacket::decode,
                BindParkMonitorPacket::handle);
        CHANNEL.registerMessage(id++, DroneInputPacket.class,
                DroneInputPacket::encode,
                DroneInputPacket::decode,
                DroneInputPacket::handle);
        CHANNEL.registerMessage(id++, DroneActionPacket.class,
                DroneActionPacket::encode,
                DroneActionPacket::decode,
                DroneActionPacket::handle);
        CHANNEL.registerMessage(id++, UpdateDroneConfigPacket.class,
                UpdateDroneConfigPacket::encode,
                UpdateDroneConfigPacket::decode,
                UpdateDroneConfigPacket::handle);
        CHANNEL.registerMessage(id++, DroneImportWaypointsPacket.class,
                DroneImportWaypointsPacket::encode,
                DroneImportWaypointsPacket::decode,
                DroneImportWaypointsPacket::handle);
        CHANNEL.registerMessage(id++, UpdateProjectorConfigPacket.class,
                UpdateProjectorConfigPacket::encode,
                UpdateProjectorConfigPacket::decode,
                UpdateProjectorConfigPacket::handle);
        CHANNEL.registerMessage(id++, UpdateComputerConfigPacket.class,
                UpdateComputerConfigPacket::encode,
                UpdateComputerConfigPacket::decode,
                UpdateComputerConfigPacket::handle);
        CHANNEL.registerMessage(id++, SwitcherActionPacket.class,
                SwitcherActionPacket::encode,
                SwitcherActionPacket::decode,
                SwitcherActionPacket::handle);
        CHANNEL.registerMessage(id++, UpdateProMonitorPacket.class,
                UpdateProMonitorPacket::encode,
                UpdateProMonitorPacket::decode,
                UpdateProMonitorPacket::handle);
        CHANNEL.registerMessage(id++, RackConfigPacket.class,
                RackConfigPacket::encode,
                RackConfigPacket::decode,
                RackConfigPacket::handle);
        CHANNEL.registerMessage(id++, ChainHoistCommandPacket.class,
                ChainHoistCommandPacket::encode,
                ChainHoistCommandPacket::decode,
                ChainHoistCommandPacket::handle);
        CHANNEL.registerMessage(id++, HoistRemotePacket.class,
                HoistRemotePacket::encode,
                HoistRemotePacket::decode,
                HoistRemotePacket::handle);
        CHANNEL.registerMessage(id++, HoistGroupListPacket.class,
                HoistGroupListPacket::encode,
                HoistGroupListPacket::decode,
                HoistGroupListPacket::handle);
    }
}
