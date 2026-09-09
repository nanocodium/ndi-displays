package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ActiveCamInputPacket(BlockPos controller, float moveX, float moveZ, float moveY,
                                   float lookYaw, float lookPitch, float zoom) {

    public static void encode(ActiveCamInputPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.controller);
        buf.writeFloat(msg.moveX);
        buf.writeFloat(msg.moveZ);
        buf.writeFloat(msg.moveY);
        buf.writeFloat(msg.lookYaw);
        buf.writeFloat(msg.lookPitch);
        buf.writeFloat(msg.zoom);
    }

    public static ActiveCamInputPacket decode(FriendlyByteBuf buf) {
        return new ActiveCamInputPacket(buf.readBlockPos(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(ActiveCamInputPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(msg.controller)
                    instanceof ActiveCamControllerBlockEntity ctrl)) {
                return;
            }
            boolean operator = player.getUUID().equals(ctrl.getOperatorId());
            if (!operator && !NetworkHandler.mayConfigure(player, msg.controller)) {
                return;
            }
            ctrl.applyInput(msg.moveX, msg.moveZ, msg.moveY, msg.lookYaw, msg.lookPitch, msg.zoom,
                    player.getUUID());
        });
        ctx.get().setPacketHandled(true);
    }
}
