package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** S2C pose snapshot ~10 Hz. Client Hermite-interpolates; never sent per render frame. */
public record ActiveCamSnapshotPacket(BlockPos controller, long gameTime, ActiveCamPose pose,
                                      boolean live, boolean eStop, boolean recording,
                                      boolean playing) {

    public static void encode(ActiveCamSnapshotPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.controller);
        buf.writeLong(msg.gameTime);
        msg.pose.write(buf);
        buf.writeBoolean(msg.live);
        buf.writeBoolean(msg.eStop);
        buf.writeBoolean(msg.recording);
        buf.writeBoolean(msg.playing);
    }

    public static ActiveCamSnapshotPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        long time = buf.readLong();
        ActiveCamPose pose = ActiveCamPose.read(buf);
        return new ActiveCamSnapshotPacket(pos, time, pose, buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(ActiveCamSnapshotPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.nano.ndidisplays.client.ActiveCamClientState.apply(msg)));
        ctx.get().setPacketHandled(true);
    }

    public static void send(ServerLevel level, ActiveCamControllerBlockEntity ctrl) {
        ActiveCamPose pose = ctrl.pose().copy();
        ActiveCamSnapshotPacket packet = new ActiveCamSnapshotPacket(
                ctrl.getBlockPos(), level.getGameTime(), pose, ctrl.isLive(), ctrl.isEStop(),
                false, ctrl.waypoints().isPlaying());
        java.util.UUID operator = ctrl.getOperatorId();
        if (operator != null && level.getServer() != null) {
            var player = level.getServer().getPlayerList().getPlayer(operator);
            if (player != null && player.serverLevel() == level) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
        BlockPos desk = ctrl.getBlockPos();
        NetworkHandler.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                pose.x, pose.y, pose.z, 256.0, level.dimension())), packet);
        NetworkHandler.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                desk.getX() + 0.5, desk.getY() + 0.5, desk.getZ() + 0.5, 256.0, level.dimension())), packet);
    }
}
