package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.SwitcherBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: one switcher panel action — a bus press, CUT, AUTO, a style or rate change,
 * or an input assignment. The server applies it (re-clamped) and syncs, so every operator's
 * panel and every published frame agree.
 */
public record SwitcherActionPacket(BlockPos pos, int op, int index, String text) {

    public static void encode(SwitcherActionPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.op);
        buf.writeVarInt(msg.index);
        buf.writeUtf(msg.text, 160);
    }

    public static SwitcherActionPacket decode(FriendlyByteBuf buf) {
        return new SwitcherActionPacket(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(160));
    }

    public static void handle(SwitcherActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof SwitcherBlockEntity sw)) {
                return;
            }
            sw.handleAction(msg.op, msg.index, msg.text);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
