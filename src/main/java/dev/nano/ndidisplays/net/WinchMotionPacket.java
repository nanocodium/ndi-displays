package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: the DMX-driven motion state of many kinetic winches in one packet.
 *
 * A winch used to sync every DMX frame through {@code sendBlockUpdated}, which ships its
 * whole NBT (forty-odd tags, strings, a UUID) as a block-entity data packet, plus a
 * section-update packet, per winch, per frame. A desk sweeping a few hundred tiles at
 * 40 Hz turned that into tens of thousands of packets a second to serialise, compress,
 * and re-parse — the stutter a big flown matrix showed. This carries only what the
 * client's own motion profile needs (targets, speed, dimmer, colours), about 30 bytes a
 * winch, batched once per tick per player by {@link WinchMotionSync}.
 *
 * The payload is opaque bytes written and read by the block entity itself, so the field
 * list lives in one place.
 */
public record WinchMotionPacket(byte[] data, int count) {

    public static void encode(WinchMotionPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.count);
        buf.writeByteArray(msg.data);
    }

    public static WinchMotionPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new WinchMotionPacket(data, count);
    }

    public static void handle(WinchMotionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> applyClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    private static void applyClient(WinchMotionPacket msg) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data));
        try {
            for (int i = 0; i < msg.count; i++) {
                BlockPos pos = buf.readBlockPos();
                if (level.getBlockEntity(pos) instanceof KineticWinchBlockEntity winch) {
                    winch.readMotion(buf);
                } else {
                    // Not loaded here (or replaced): skip its bytes to stay aligned.
                    KineticWinchBlockEntity.skipMotion(buf);
                }
            }
        } finally {
            buf.release();
        }
    }
}
