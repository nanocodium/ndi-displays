package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.NdiRouterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S: repatch a router's output name and source, validated server-side. */
public record UpdateRouterConfigPacket(BlockPos pos, String outputName, String sourceName,
                                      int pattern, int patternResolution, int patternFps) {

    public static void encode(UpdateRouterConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.outputName, NdiRouterBlockEntity.MAX_NAME);
        buf.writeUtf(msg.sourceName, NdiRouterBlockEntity.MAX_NAME);
        buf.writeVarInt(msg.pattern);
        buf.writeVarInt(msg.patternResolution);
        buf.writeVarInt(msg.patternFps);
    }

    public static UpdateRouterConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateRouterConfigPacket(buf.readBlockPos(),
                buf.readUtf(NdiRouterBlockEntity.MAX_NAME),
                buf.readUtf(NdiRouterBlockEntity.MAX_NAME),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt());
    }

    public static void handle(UpdateRouterConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            Level level = player.level();
            if (level.getBlockEntity(msg.pos) instanceof NdiRouterBlockEntity router) {
                router.applyConfig(msg.outputName, msg.sourceName, msg.pattern,
                        msg.patternResolution, msg.patternFps);
                level.sendBlockUpdated(msg.pos, router.getBlockState(), router.getBlockState(),
                        Block.UPDATE_ALL);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
