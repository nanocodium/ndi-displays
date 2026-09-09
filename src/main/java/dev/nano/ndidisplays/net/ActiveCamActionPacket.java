package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ActiveCamActionPacket(BlockPos pos, Action action) {

    public enum Action {
        RECORD, PLAY, PAUSE, STOP, LOOP, ESTOP, CLEAR_ESTOP, TAKE_CONTROL, RELEASE_CONTROL, CLEAR_PATH
    }

    public static void encode(ActiveCamActionPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.action.ordinal());
    }

    public static ActiveCamActionPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int ord = buf.readVarInt();
        Action action = ord >= 0 && ord < Action.values().length ? Action.values()[ord] : Action.STOP;
        return new ActiveCamActionPacket(pos, action);
    }

    public static void handle(ActiveCamActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(msg.pos) instanceof ActiveCamControllerBlockEntity ctrl)) {
                return;
            }
            boolean operator = player.getUUID().equals(ctrl.getOperatorId());
            boolean riding = player.getVehicle() instanceof dev.nano.ndidisplays.entity.ActiveCamGondolaEntity gondola
                    && msg.pos.equals(gondola.controllerPos());
            if (!operator && !riding && !NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            switch (msg.action) {
                case RECORD -> ctrl.addWaypointHere();
                case PLAY -> ctrl.waypoints().play();
                case PAUSE -> ctrl.waypoints().pause();
                case STOP -> ctrl.waypoints().stop();
                case LOOP -> ctrl.waypoints().setMode(ctrl.waypoints().mode().next());
                case ESTOP -> ctrl.emergencyStop();
                case CLEAR_ESTOP -> ctrl.clearEStop();
                case TAKE_CONTROL -> ctrl.takeControl(player);
                case RELEASE_CONTROL -> ctrl.releaseControl(player);
                case CLEAR_PATH -> ctrl.waypoints().clear();
            }
            ctrl.setChanged();
            net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(msg.pos);
            player.level().sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
