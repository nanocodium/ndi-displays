package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ComputerBlockEntity;
import dev.nano.ndidisplays.block.WebTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: a computer's configuration. Only a player the computer accepts
 * ({@link ComputerBlockEntity#mayUse}) may change it — the lock includes the lock itself.
 */
public record UpdateComputerConfigPacket(BlockPos pos, String name, int resolution, int fps,
                                         boolean broadcast, boolean locked) {

    public static void encode(UpdateComputerConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.name, WebTerminalBlockEntity.MAX_LABEL);
        buf.writeVarInt(msg.resolution);
        buf.writeVarInt(msg.fps);
        buf.writeBoolean(msg.broadcast);
        buf.writeBoolean(msg.locked);
    }

    public static UpdateComputerConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateComputerConfigPacket(
                buf.readBlockPos(),
                buf.readUtf(WebTerminalBlockEntity.MAX_LABEL),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public static void handle(UpdateComputerConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof ComputerBlockEntity pc)
                    || !pc.mayUse(player)) {
                return;
            }
            pc.applyComputerConfig(msg.name, msg.resolution, msg.fps, msg.broadcast, msg.locked);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
