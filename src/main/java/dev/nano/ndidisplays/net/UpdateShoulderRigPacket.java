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
 * Client → server: a new aim for the sender's shoulder rig. Values are re-clamped in
 * {@link ShoulderCameraItem#setAim} rather than trusted.
 *
 * The aim lives on the item stack, so writing it server-side is what makes it persist and
 * reach other clients — they render the rig from the same stack, so the visible lens angle
 * matches the feed for everyone.
 */
public record UpdateShoulderRigPacket(float pan, float tilt, float fov) {

    public static void encode(UpdateShoulderRigPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.pan);
        buf.writeFloat(msg.tilt);
        buf.writeFloat(msg.fov);
    }

    public static UpdateShoulderRigPacket decode(FriendlyByteBuf buf) {
        return new UpdateShoulderRigPacket(buf.readFloat(), buf.readFloat(), buf.readFloat());
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
            ShoulderCameraItem.setAim(worn, msg.pan, msg.tilt, msg.fov);
        });
        ctx.get().setPacketHandled(true);
    }
}
