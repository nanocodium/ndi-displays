package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.MultiviewBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: full configuration of one multiview monitor. Values are
 * re-clamped server side in {@link MultiviewBlockEntity#applyConfig}.
 */
public record UpdateMultiviewConfigPacket(BlockPos pos, int layout, float width,
                                          float brightness, String[] sources) {

    public static void encode(UpdateMultiviewConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.layout);
        buf.writeFloat(msg.width);
        buf.writeFloat(msg.brightness);
        buf.writeVarInt(MultiviewBlockEntity.MAX_CELLS);
        for (int i = 0; i < MultiviewBlockEntity.MAX_CELLS; i++) {
            buf.writeUtf(i < msg.sources.length ? msg.sources[i] : "", 256);
        }
    }

    public static UpdateMultiviewConfigPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int layout = buf.readVarInt();
        float width = buf.readFloat();
        float brightness = buf.readFloat();
        int count = Math.min(buf.readVarInt(), MultiviewBlockEntity.MAX_CELLS);
        String[] sources = new String[MultiviewBlockEntity.MAX_CELLS];
        for (int i = 0; i < MultiviewBlockEntity.MAX_CELLS; i++) {
            sources[i] = i < count ? buf.readUtf(256) : "";
        }
        return new UpdateMultiviewConfigPacket(pos, layout, width, brightness, sources);
    }

    public static void handle(UpdateMultiviewConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof MultiviewBlockEntity monitor)) {
                return;
            }
            monitor.applyConfig(msg.layout, msg.width, msg.brightness, msg.sources);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
