package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.entity.DroneEntity;
import dev.nano.ndidisplays.path.DronePath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S: path transport controls and waypoint edits from the remote GUI / FPV key. */
public record DroneActionPacket(UUID droneId, Action action, int index) {

    public enum Action {
        ADD_HERE, REMOVE, MOVE_UP, MOVE_DOWN, PLAY, STOP, CYCLE_MODE, CLEAR, EXIT
    }

    public static void encode(DroneActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.droneId);
        buf.writeVarInt(msg.action.ordinal());
        buf.writeVarInt(msg.index);
    }

    public static DroneActionPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int ord = buf.readVarInt();
        Action action = ord >= 0 && ord < Action.values().length ? Action.values()[ord] : Action.STOP;
        return new DroneActionPacket(id, action, buf.readVarInt());
    }

    public static void handle(DroneActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            DroneEntity drone = DroneEntity.find(player.level(), msg.droneId);
            if (drone == null) {
                return;
            }
            if (msg.action == Action.EXIT) {
                if (player.getVehicle() == drone) {
                    drone.exitPilot(player);
                }
                return;
            }
            if (!NetworkHandler.mayConfigureEntity(player, drone)) {
                return;
            }
            DronePath path = drone.path();
            switch (msg.action) {
                case ADD_HERE -> {
                    if (player.getVehicle() == drone) {
                        drone.addWaypointHere();
                    } else {
                        path.add(new DronePath.Waypoint(player.position().add(0.0, 1.0, 0.0),
                                player.getYRot(), player.getXRot(),
                                Math.max(1.0F, drone.getMaxSpeed() * 0.5F), 0));
                        drone.syncPath();
                    }
                    player.displayClientMessage(Component.translatable(
                            "gui.ndidisplays.drone.waypoint_added", path.size()), true);
                }
                case REMOVE -> {
                    path.remove(msg.index);
                    drone.syncPath();
                }
                case MOVE_UP -> {
                    path.move(msg.index, -1);
                    drone.syncPath();
                }
                case MOVE_DOWN -> {
                    path.move(msg.index, 1);
                    drone.syncPath();
                }
                case PLAY -> {
                    path.play();
                    drone.syncPath();
                }
                case STOP -> {
                    path.stop();
                    drone.syncPath();
                }
                case CYCLE_MODE -> {
                    path.setMode(path.mode().next());
                    drone.syncPath();
                }
                case CLEAR -> {
                    path.clear();
                    drone.syncPath();
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
