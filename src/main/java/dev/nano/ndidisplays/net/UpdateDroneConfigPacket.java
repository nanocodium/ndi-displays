package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.entity.DroneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: NDI + flight config for a linked drone. */
public record UpdateDroneConfigPacket(UUID droneId, String source, boolean live, int resolution,
                                      int fps, float fov, float maxSpeed) {

    public static void encode(UpdateDroneConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.droneId);
        buf.writeUtf(msg.source, DroneEntity.MAX_SOURCE_NAME);
        buf.writeBoolean(msg.live);
        buf.writeVarInt(msg.resolution);
        buf.writeVarInt(msg.fps);
        buf.writeFloat(msg.fov);
        buf.writeFloat(msg.maxSpeed);
    }

    public static UpdateDroneConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateDroneConfigPacket(buf.readUUID(), buf.readUtf(DroneEntity.MAX_SOURCE_NAME),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(UpdateDroneConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            DroneEntity drone = DroneEntity.find(player.level(), msg.droneId);
            if (drone == null || !NetworkHandler.mayConfigureEntity(player, drone)) {
                return;
            }
            drone.applyConfig(msg.source, msg.live, msg.resolution, msg.fps, msg.fov, msg.maxSpeed);
        });
        ctx.get().setPacketHandled(true);
    }
}
