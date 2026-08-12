package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.item.NdiConfigCardItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: store the chosen NDI source on the configuration card the player
 * is holding. Written server-side so the card's NBT is authoritative and survives.
 */
public record UpdateNdiCardPacket(boolean mainHand, String source) {

    public static void encode(UpdateNdiCardPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.mainHand);
        buf.writeUtf(msg.source, LedPanelBlockEntity.MAX_SOURCE_NAME);
    }

    public static UpdateNdiCardPacket decode(FriendlyByteBuf buf) {
        return new UpdateNdiCardPacket(buf.readBoolean(), buf.readUtf(LedPanelBlockEntity.MAX_SOURCE_NAME));
    }

    public static void handle(UpdateNdiCardPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            InteractionHand hand = msg.mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NdiConfigCardItem) {
                stack.getOrCreateTag().putString(NdiConfigCardItem.TAG_SOURCE, msg.source.trim());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
