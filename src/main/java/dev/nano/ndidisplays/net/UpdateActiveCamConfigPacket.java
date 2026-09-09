package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.activecam.ActiveCamHeadMode;
import dev.nano.ndidisplays.activecam.ActiveCamMode;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

public record UpdateActiveCamConfigPacket(BlockPos pos, String source, boolean live, int resolution,
                                          int fps, float workingHeight, float maxSpeed, float accel,
                                          int mode, int headMode, boolean hasTarget,
                                          @Nullable UUID target) {

    public static void encode(UpdateActiveCamConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, ActiveCamControllerBlockEntity.MAX_SOURCE_NAME);
        buf.writeBoolean(msg.live);
        buf.writeVarInt(msg.resolution);
        buf.writeVarInt(msg.fps);
        buf.writeFloat(msg.workingHeight);
        buf.writeFloat(msg.maxSpeed);
        buf.writeFloat(msg.accel);
        buf.writeVarInt(msg.mode);
        buf.writeVarInt(msg.headMode);
        buf.writeBoolean(msg.hasTarget);
        if (msg.hasTarget && msg.target != null) {
            buf.writeUUID(msg.target);
        }
    }

    public static UpdateActiveCamConfigPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String source = buf.readUtf(ActiveCamControllerBlockEntity.MAX_SOURCE_NAME);
        boolean live = buf.readBoolean();
        int resolution = buf.readVarInt();
        int fps = buf.readVarInt();
        float height = buf.readFloat();
        float speed = buf.readFloat();
        float accel = buf.readFloat();
        int mode = buf.readVarInt();
        int head = buf.readVarInt();
        boolean hasTarget = buf.readBoolean();
        UUID target = hasTarget ? buf.readUUID() : null;
        return new UpdateActiveCamConfigPacket(pos, source, live, resolution, fps, height, speed,
                accel, mode, head, hasTarget, target);
    }

    public static void handle(UpdateActiveCamConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(player.level().getBlockEntity(msg.pos) instanceof ActiveCamControllerBlockEntity ctrl)) {
                return;
            }
            ctrl.applyConfig(msg.source, msg.live, msg.resolution, msg.fps, msg.workingHeight,
                    msg.maxSpeed, msg.accel, ActiveCamMode.fromOrdinal(msg.mode),
                    ActiveCamHeadMode.fromOrdinal(msg.headMode),
                    msg.hasTarget ? msg.target : null);
        });
        ctx.get().setPacketHandled(true);
    }
}
