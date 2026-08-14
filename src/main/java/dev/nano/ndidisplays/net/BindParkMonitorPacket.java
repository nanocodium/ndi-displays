package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.WinchParkMonitorBlockEntity;
import dev.nano.ndidisplays.item.NdiConfigCardItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: bind a winch-park monitor to the NDI card's WorldEdit selection.
 * The box is read from the server's copy of the held card, never from the packet.
 */
public record BindParkMonitorPacket(BlockPos monitor) {

    public static void encode(BindParkMonitorPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.monitor);
    }

    public static BindParkMonitorPacket decode(FriendlyByteBuf buf) {
        return new BindParkMonitorPacket(buf.readBlockPos());
    }

    public static void handle(BindParkMonitorPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.monitor)) {
                return;
            }
            if (!(level.getBlockEntity(msg.monitor) instanceof WinchParkMonitorBlockEntity monitor)) {
                return;
            }
            ItemStack card = cardWithSelection(player);
            if (card == null) {
                return;
            }
            BlockPos pos1 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS1);
            BlockPos pos2 = NdiConfigCardItem.selectionPos(card, NdiConfigCardItem.TAG_POS2);
            String dim = NdiConfigCardItem.selectionDimension(card);
            if (pos1 == null || pos2 == null || dim.isEmpty()) {
                return;
            }
            monitor.bind(pos1, pos2, dim);
            BlockState state = level.getBlockState(msg.monitor);
            level.sendBlockUpdated(msg.monitor, state, state, 3);
            player.displayClientMessage(Component.translatable("gui.ndidisplays.park.bound"), true);
        });
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack cardWithSelection(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NdiConfigCardItem && NdiConfigCardItem.hasSelection(stack)) {
                return stack;
            }
        }
        return null;
    }
}
