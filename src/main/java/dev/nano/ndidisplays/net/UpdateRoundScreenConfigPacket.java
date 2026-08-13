package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.RoundScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: full configuration of one circular screen. Values are re-clamped
 * server side in {@link RoundScreenBlockEntity#applyConfig}.
 */
public record UpdateRoundScreenConfigPacket(BlockPos pos, String source, int pxPerBlock,
                                            float brightness, int pattern, float radius) {

    public static void encode(UpdateRoundScreenConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 256);
        buf.writeVarInt(msg.pxPerBlock);
        buf.writeFloat(msg.brightness);
        buf.writeVarInt(msg.pattern);
        buf.writeFloat(msg.radius);
    }

    public static UpdateRoundScreenConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateRoundScreenConfigPacket(
                buf.readBlockPos(),
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readFloat());
    }

    public static void handle(UpdateRoundScreenConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof RoundScreenBlockEntity screen)) {
                return;
            }
            screen.applyConfig(msg.source, msg.pxPerBlock, msg.brightness, msg.pattern, msg.radius);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
