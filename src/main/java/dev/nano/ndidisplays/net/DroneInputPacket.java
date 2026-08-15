package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.entity.DroneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: one tick of FPV stick + look while the sender is riding that drone. */
public record DroneInputPacket(UUID droneId, float forward, float strafe, float vertical,
                               float yaw, float pitch) {

    public static void encode(DroneInputPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.droneId);
        buf.writeFloat(msg.forward);
        buf.writeFloat(msg.strafe);
        buf.writeFloat(msg.vertical);
        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);
    }

    public static DroneInputPacket decode(FriendlyByteBuf buf) {
        return new DroneInputPacket(buf.readUUID(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(DroneInputPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            DroneEntity drone = player.getVehicle() instanceof DroneEntity ridden
                    ? ridden
                    : DroneEntity.find(player.level(), msg.droneId);
            if (drone == null || player.getVehicle() != drone) {
                return;
            }
            drone.applyPilotInput(msg.forward, msg.strafe, msg.vertical, msg.yaw, msg.pitch);
        });
        ctx.get().setPacketHandled(true);
    }
}
