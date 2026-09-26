package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.item.ShoulderCameraItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: new feed settings for the sender's shoulder rig. Values are re-clamped in
 * {@link ShoulderCameraItem#setConfig} rather than trusted.
 *
 * The settings live on the item stack, so writing them server-side is what makes them persist
 * and reach other clients — they render the rig from the same stack, so the visible lens angle
 * matches the feed for everyone.
 */
public record UpdateShoulderRigPacket(String source, boolean live, int resolution, int fps,
                                      float fov) {

    public static void encode(UpdateShoulderRigPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.source, ShoulderCameraItem.MAX_SOURCE_LENGTH);
        buf.writeBoolean(msg.live);
        buf.writeVarInt(msg.resolution);
        buf.writeVarInt(msg.fps);
        buf.writeFloat(msg.fov);
    }

    public static UpdateShoulderRigPacket decode(FriendlyByteBuf buf) {
        return new UpdateShoulderRigPacket(buf.readUtf(ShoulderCameraItem.MAX_SOURCE_LENGTH),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readFloat());
    }

    public static void handle(UpdateShoulderRigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            // Only ever the sender's own worn rig: no position to validate, and nothing here
            // can touch another player's equipment.
            ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
            if (!worn.is(NdiDisplays.SHOULDER_CAMERA_ITEM.get())) {
                // Not worn — fall back to a held one, so the rig can be set up before wearing.
                worn = player.getMainHandItem().is(NdiDisplays.SHOULDER_CAMERA_ITEM.get())
                        ? player.getMainHandItem()
                        : player.getOffhandItem();
                if (!worn.is(NdiDisplays.SHOULDER_CAMERA_ITEM.get())) {
                    return;
                }
            }
            ShoulderCameraItem.setConfig(worn, msg.source, msg.live, msg.resolution, msg.fps,
                    msg.fov);
        });
        ctx.get().setPacketHandled(true);
    }
}
