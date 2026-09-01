package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: full optical configuration of one projector. Values are re-clamped server
 * side in {@link ProjectorBlockEntity#applyConfig}.
 */
public record UpdateProjectorConfigPacket(BlockPos pos, String source, int pattern, float yaw,
                                          float pitch, float fov, float aspect, float near,
                                          float far, float keystoneH, float keystoneV,
                                          float shiftH, float shiftV, float brightness,
                                          float feather, boolean additive, boolean showFrustum) {

    public static void encode(UpdateProjectorConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.source, 256);
        buf.writeVarInt(msg.pattern);
        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);
        buf.writeFloat(msg.fov);
        buf.writeFloat(msg.aspect);
        buf.writeFloat(msg.near);
        buf.writeFloat(msg.far);
        buf.writeFloat(msg.keystoneH);
        buf.writeFloat(msg.keystoneV);
        buf.writeFloat(msg.shiftH);
        buf.writeFloat(msg.shiftV);
        buf.writeFloat(msg.brightness);
        buf.writeFloat(msg.feather);
        buf.writeBoolean(msg.additive);
        buf.writeBoolean(msg.showFrustum);
    }

    public static UpdateProjectorConfigPacket decode(FriendlyByteBuf buf) {
        return new UpdateProjectorConfigPacket(
                buf.readBlockPos(),
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(UpdateProjectorConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            if (!(level.getBlockEntity(msg.pos) instanceof ProjectorBlockEntity projector)) {
                return;
            }
            projector.applyConfig(msg.source, msg.pattern, msg.yaw, msg.pitch, msg.fov, msg.aspect,
                    msg.near, msg.far, msg.keystoneH, msg.keystoneV, msg.shiftH, msg.shiftV,
                    msg.brightness, msg.feather, msg.additive, msg.showFrustum);
            BlockState state = level.getBlockState(msg.pos);
            level.sendBlockUpdated(msg.pos, state, state, 3);
        });
        ctx.get().setPacketHandled(true);
    }
}
