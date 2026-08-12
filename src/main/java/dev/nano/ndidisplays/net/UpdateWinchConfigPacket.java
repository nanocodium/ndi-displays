package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → server: full configuration of one kinetic winch. Values are re-clamped
 * server side in {@link KineticWinchBlockEntity#applyConfig}. The DMX consumer is
 * re-registered around the change because the network/universe/address the consumer
 * is keyed under may all move.
 */
public record UpdateWinchConfigPacket(BlockPos pos, String source, int pxPerBlock,
                                      float brightness, int pattern,
                                      int canvasCols, int canvasRows, int canvasCol, int canvasRow,
                                      int panelW, int panelH, int orientation, boolean mesh,
                                      float minDrop, float maxDrop, float speed, float targetDrop,
                                      int dmxUniverse, int dmxAddress, UUID networkId) {

    public static void encode(UpdateWinchConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 256);
        buf.writeVarInt(msg.pxPerBlock);
        buf.writeFloat(msg.brightness);
        buf.writeVarInt(msg.pattern);
        buf.writeVarInt(msg.canvasCols);
        buf.writeVarInt(msg.canvasRows);
        buf.writeVarInt(msg.canvasCol);
        buf.writeVarInt(msg.canvasRow);
        buf.writeVarInt(msg.panelW);
        buf.writeVarInt(msg.panelH);
        buf.writeVarInt(msg.orientation);
        buf.writeBoolean(msg.mesh);
        buf.writeFloat(msg.minDrop);
        buf.writeFloat(msg.maxDrop);
        buf.writeFloat(msg.speed);
        buf.writeFloat(msg.targetDrop);
        buf.writeVarInt(msg.dmxUniverse);
        buf.writeVarInt(msg.dmxAddress);
        buf.writeUUID(msg.networkId);
    }

    public static UpdateWinchConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateWinchConfigPacket(
                buf.readBlockPos(),
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUUID());
    }

    public static void handle(UpdateWinchConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof KineticWinchBlockEntity winch)) {
                return;
            }
            // Remove while the consumer still reports its old network/universe key,
            // otherwise a stale registration would keep receiving the old address.
            TheatricalCompat.unregister(winch);
            winch.applyConfig(msg.source, msg.pxPerBlock, msg.brightness, msg.pattern,
                    msg.canvasCols, msg.canvasRows, msg.canvasCol, msg.canvasRow,
                    msg.panelW, msg.panelH, msg.orientation, msg.mesh,
                    msg.minDrop, msg.maxDrop, msg.speed, msg.targetDrop,
                    msg.dmxUniverse, msg.dmxAddress, msg.networkId);
            TheatricalCompat.register(winch);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
