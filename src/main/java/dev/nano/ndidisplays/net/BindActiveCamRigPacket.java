package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import dev.nano.ndidisplays.block.CameraWinchBlockEntity;
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

/** Bind four Camera Winches from the held NDI card onto a controller. Coords from the card. */
public record BindActiveCamRigPacket(BlockPos controller) {

    public static void encode(BindActiveCamRigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.controller);
    }

    public static BindActiveCamRigPacket decode(FriendlyByteBuf buf) {
        return new BindActiveCamRigPacket(buf.readBlockPos());
    }

    public static void handle(BindActiveCamRigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !NetworkHandler.mayConfigure(player, msg.controller)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!(level.getBlockEntity(msg.controller) instanceof ActiveCamControllerBlockEntity ctrl)) {
                return;
            }
            ItemStack card = cardWithWinches(player);
            if (card == null) {
                return;
            }
            BlockPos[] corners = NdiConfigCardItem.cameraWinches(card);
            if (corners == null) {
                return;
            }
            for (BlockPos w : corners) {
                if (!level.isLoaded(w) || !(level.getBlockEntity(w) instanceof CameraWinchBlockEntity winch)) {
                    player.displayClientMessage(Component.translatable(
                            "gui.ndidisplays.activecam.bind_missing"), true);
                    return;
                }
                BlockPos existing = winch.getControllerPos();
                if (existing != null && !existing.equals(msg.controller)) {
                    player.displayClientMessage(Component.translatable(
                            "gui.ndidisplays.activecam.bind_busy"), true);
                    return;
                }
            }
            ctrl.unbindWinches();
            if (!ctrl.bindWinches(corners)) {
                return;
            }
            for (BlockPos w : corners) {
                if (level.getBlockEntity(w) instanceof CameraWinchBlockEntity winch) {
                    winch.bind(msg.controller);
                    BlockState st = level.getBlockState(w);
                    level.sendBlockUpdated(w, st, st, 3);
                }
            }
            BlockState state = level.getBlockState(msg.controller);
            level.sendBlockUpdated(msg.controller, state, state, 3);
            player.displayClientMessage(Component.translatable("gui.ndidisplays.activecam.bound"), true);
        });
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack cardWithWinches(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NdiConfigCardItem
                    && NdiConfigCardItem.hasCameraWinches(stack)) {
                return stack;
            }
        }
        return null;
    }
}
