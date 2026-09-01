package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ProMonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: a production monitor's source and brightness. */
public record UpdateProMonitorPacket(BlockPos pos, String source, float brightness) {

    public static void encode(UpdateProMonitorPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 160);
        buf.writeFloat(msg.brightness);
    }

    public static UpdateProMonitorPacket decode(FriendlyByteBuf buf) {
        return new UpdateProMonitorPacket(buf.readBlockPos(), buf.readUtf(160), buf.readFloat());
    }

    public static void handle(UpdateProMonitorPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof ProMonitorBlockEntity mon)) {
                return;
            }
            mon.applyConfig(msg.source, msg.brightness);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
