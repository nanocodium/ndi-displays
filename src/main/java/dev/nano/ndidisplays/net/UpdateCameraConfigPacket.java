package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S: apply a camera rig's configuration server-side (validated by distance). */
public record UpdateCameraConfigPacket(BlockPos pos, String source, boolean active, int resolution,
                                       int fps, float fov, float pan, float tilt,
                                       float aux1, float aux2, float aux3) {

    public static void encode(UpdateCameraConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 128);
        buf.writeBoolean(msg.active);
        buf.writeVarInt(msg.resolution);
        buf.writeVarInt(msg.fps);
        buf.writeFloat(msg.fov);
        buf.writeFloat(msg.pan);
        buf.writeFloat(msg.tilt);
        buf.writeFloat(msg.aux1);
        buf.writeFloat(msg.aux2);
        buf.writeFloat(msg.aux3);
    }

    public static UpdateCameraConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateCameraConfigPacket(buf.readBlockPos(), buf.readUtf(128), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(UpdateCameraConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            Level level = player.level();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (level.getBlockEntity(msg.pos) instanceof NdiCameraBlockEntity camera) {
                camera.applyConfig(msg.source, msg.active, msg.resolution, msg.fps, msg.fov,
                        msg.pan, msg.tilt, msg.aux1, msg.aux2, msg.aux3);
                level.sendBlockUpdated(msg.pos, camera.getBlockState(), camera.getBlockState(), Block.UPDATE_ALL);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
