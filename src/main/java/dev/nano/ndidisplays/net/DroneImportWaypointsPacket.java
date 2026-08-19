package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.entity.DroneEntity;
import dev.nano.ndidisplays.path.DronePath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** C2S: append imported world positions (Xaero, etc.) to the drone path. */
public record DroneImportWaypointsPacket(UUID droneId, List<Vec3> points) {

    public static void encode(DroneImportWaypointsPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.droneId);
        buf.writeVarInt(msg.points.size());
        for (Vec3 point : msg.points) {
            buf.writeDouble(point.x);
            buf.writeDouble(point.y);
            buf.writeDouble(point.z);
        }
    }

    public static DroneImportWaypointsPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int count = Math.min(DronePath.MAX_WAYPOINTS, Math.max(0, buf.readVarInt()));
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return new DroneImportWaypointsPacket(id, points);
    }

    public static void handle(DroneImportWaypointsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            DroneEntity drone = DroneEntity.find(player.level(), msg.droneId);
            if (drone == null || !NetworkHandler.mayConfigureEntity(player, drone)) {
                return;
            }
            DronePath path = drone.path();
            float speed = Math.max(1.0F, drone.getMaxSpeed() * 0.5F);
            for (Vec3 pos : msg.points) {
                if (path.size() >= DronePath.MAX_WAYPOINTS) {
                    break;
                }
                path.add(new DronePath.Waypoint(pos, drone.heading(), drone.gimbalPitch(), speed, 0));
            }
            drone.syncPath();
            player.displayClientMessage(Component.translatable(
                    "gui.ndidisplays.drone.imported", path.size()), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
