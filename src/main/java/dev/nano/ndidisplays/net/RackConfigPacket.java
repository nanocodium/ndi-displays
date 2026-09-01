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

/**
 * Client → server: one configuration key of one rack slot. The server only accepts the keys the
 * seated unit actually has: Url on a web module; Name and Source on a router.
 */
public record RackConfigPacket(BlockPos pos, int slot, String key, String value) {

    public static void encode(RackConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.slot);
        buf.writeUtf(msg.key, 16);
        buf.writeUtf(msg.value, 512);
    }

    public static RackConfigPacket decode(FriendlyByteBuf buf) {
        return new RackConfigPacket(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(16),
                buf.readUtf(512));
    }

    private static boolean allowed(RackUnitType type, String key) {
        return switch (type) {
            case WEB -> key.equals("Url");
            case ROUTER -> key.equals("Name") || key.equals("Source");
            default -> false;
        };
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
            if (!(level.getBlockEntity(msg.pos) instanceof RackBlockEntity rack)) {
                return;
            }
            RackUnitType type = rack.unit(msg.slot);
            if (type == null || !allowed(type, msg.key)) {
                return;
            }
            rack.cfg(msg.slot).putString(msg.key,
                    dev.nano.ndidisplays.block.Clamps.name(msg.value, 512));
            rack.setChanged();
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
