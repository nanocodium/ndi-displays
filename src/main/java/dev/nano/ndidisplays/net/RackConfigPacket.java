package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.RackBlockEntity;
import dev.nano.ndidisplays.block.RackUnitType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: a rack web module's URL. */
public record RackConfigPacket(BlockPos pos, int slot, String url) {

    public static void encode(RackConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.slot);
        buf.writeUtf(msg.url, 512);
    }

    public static RackConfigPacket decode(FriendlyByteBuf buf) {
        return new RackConfigPacket(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(512));
    }

    public static void handle(RackConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof RackBlockEntity rack)
                    || rack.unit(msg.slot) != RackUnitType.WEB) {
                return;
            }
            rack.cfg(msg.slot).putString("Url",
                    dev.nano.ndidisplays.block.Clamps.name(msg.url, 512));
            rack.setChanged();
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
