package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.FloorScanner;
import dev.nano.ndidisplays.block.LedFloorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: apply display settings to every tile of the floor containing {@code pos}.
 */
public record UpdateFloorConfigPacket(BlockPos pos, String source, int pxPerBlock,
                                      float brightness, float gamma, int pattern) {

    public static void encode(UpdateFloorConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 256);
        buf.writeVarInt(msg.pxPerBlock);
        buf.writeFloat(msg.brightness);
        buf.writeFloat(msg.gamma);
        buf.writeVarInt(msg.pattern);
    }

    public static UpdateFloorConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateFloorConfigPacket(
                buf.readBlockPos(),
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt());
    }

    public static void handle(UpdateFloorConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof LedFloorBlockEntity clicked)) {
                return;
            }
            for (BlockPos tilePos : FloorScanner.collectGroup(level, msg.pos, clicked.getFacing(),
                    clicked.getPanelKind())) {
                if (level.getBlockEntity(tilePos) instanceof LedFloorBlockEntity tile) {
                    tile.applyConfig(msg.source, msg.pxPerBlock, msg.brightness, msg.gamma, msg.pattern);
                    BlockState state = level.getBlockState(tilePos);
                    level.sendBlockUpdated(tilePos, state, state, 3);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
