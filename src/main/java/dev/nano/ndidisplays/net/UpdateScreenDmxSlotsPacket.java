package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.DmxScreen;
import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.ScreenDmxState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client → server: the eight DMX source slots of one fixed screen — the NDI names
 * the desk's source-select channel cuts between.
 */
public record UpdateScreenDmxSlotsPacket(BlockPos pos, List<String> slots) {

    public static void encode(UpdateScreenDmxSlotsPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(Math.min(msg.slots.size(), ScreenDmxState.SLOTS));
        for (int i = 0; i < Math.min(msg.slots.size(), ScreenDmxState.SLOTS); i++) {
            buf.writeUtf(msg.slots.get(i), LedPanelBlockEntity.MAX_SOURCE_NAME);
        }
    }

    public static UpdateScreenDmxSlotsPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int count = Math.min(buf.readVarInt(), ScreenDmxState.SLOTS);
        List<String> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            slots.add(buf.readUtf(LedPanelBlockEntity.MAX_SOURCE_NAME));
        }
        return new UpdateScreenDmxSlotsPacket(pos, slots);
    }

    public static void handle(UpdateScreenDmxSlotsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockEntity be = level.getBlockEntity(msg.pos);
            if (!(be instanceof DmxScreen screen)) {
                return;
            }
            for (int i = 0; i < ScreenDmxState.SLOTS; i++) {
                screen.dmx().setSlot(i, i < msg.slots.size() ? msg.slots.get(i).trim() : "");
            }
            be.setChanged();
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
