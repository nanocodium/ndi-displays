package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.WebTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: a web terminal's page, label, output resolution and rate. Values are
 * re-clamped in {@link WebTerminalBlockEntity#applyConfig} rather than trusted.
 *
 * The URL is world state, not a client preference: everyone standing at the terminal should see
 * the same page, and the broadcast host needs it in order to publish the feed even if the person
 * who typed it walks away.
 */
public record UpdateWebTerminalPacket(BlockPos pos, String url, String label,
                                     int resolution, int fps, boolean broadcast) {

    public static void encode(UpdateWebTerminalPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.url, WebTerminalBlockEntity.MAX_URL);
        buf.writeUtf(msg.label, WebTerminalBlockEntity.MAX_LABEL);
        buf.writeVarInt(msg.resolution);
        buf.writeVarInt(msg.fps);
        buf.writeBoolean(msg.broadcast);
    }

    public static UpdateWebTerminalPacket decode(FriendlyByteBuf buf) {
        return new UpdateWebTerminalPacket(
                buf.readBlockPos(),
                buf.readUtf(WebTerminalBlockEntity.MAX_URL),
                buf.readUtf(WebTerminalBlockEntity.MAX_LABEL),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean());
    }

    public static void handle(UpdateWebTerminalPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof WebTerminalBlockEntity terminal)) {
                return;
            }
            terminal.applyConfig(msg.url, msg.label, msg.resolution, msg.fps, msg.broadcast);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
