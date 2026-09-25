package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record CameraAimPacket(BlockPos pos, float pan, float tilt, float fov) {
    public static void encode(CameraAimPacket m, FriendlyByteBuf b) {
        b.writeBlockPos(m.pos); b.writeFloat(m.pan); b.writeFloat(m.tilt); b.writeFloat(m.fov);
    }
    public static CameraAimPacket decode(FriendlyByteBuf b) {
        return new CameraAimPacket(b.readBlockPos(), b.readFloat(), b.readFloat(), b.readFloat());
    }
    public static void handle(CameraAimPacket m, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender();
            if (player == null || !Float.isFinite(m.pan) || !Float.isFinite(m.tilt)
                    || !Float.isFinite(m.fov) || !NetworkHandler.mayConfigure(player, m.pos)) return;
            var level = player.serverLevel();
            if (level.getBlockEntity(m.pos) instanceof NdiCameraBlockEntity camera) {
                camera.aim(m.pan, m.tilt, m.fov);
                level.sendBlockUpdated(m.pos, camera.getBlockState(), camera.getBlockState(), 2);
            }
        });
        context.get().setPacketHandled(true);
    }
}
