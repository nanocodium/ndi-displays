package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.WallScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: apply display settings to every panel of the wall containing {@code pos}.
 * Storing the config on every panel (not just the anchor) keeps it intact when the
 * wall is reshaped and the anchor moves.
 */
public record UpdateWallConfigPacket(BlockPos pos, String source, int pxPerBlock,
                                     float brightness, float gamma, int pattern) {

    public static void encode(UpdateWallConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 256);
        buf.writeVarInt(msg.pxPerBlock);
        buf.writeFloat(msg.brightness);
        buf.writeFloat(msg.gamma);
        buf.writeVarInt(msg.pattern);
    }

    public static UpdateWallConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateWallConfigPacket(
                buf.readBlockPos(),
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt());
    }

    public static void handle(UpdateWallConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof LedPanelBlockEntity clicked)) {
                return;
            }
            // Apply to every panel of the screen: the merged wall (including the far side of
            // a 90° corner or chamfer, which draws from this side's anchor) plus any connected
            // panels the scanner does not recognise yet, so a half-finished arrangement is
            // already configured once it becomes a screen.
            for (BlockPos panelPos : clicked.screenGroup()) {
                if (level.getBlockEntity(panelPos) instanceof LedPanelBlockEntity panel) {
                    panel.applyConfig(msg.source, msg.pxPerBlock, msg.brightness, msg.gamma, msg.pattern);
                    BlockState state = level.getBlockState(panelPos);
                    level.sendBlockUpdated(panelPos, state, state, 3);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
