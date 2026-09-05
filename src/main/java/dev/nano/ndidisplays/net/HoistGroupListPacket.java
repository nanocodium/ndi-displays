package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.hoist.HoistGroupSnapshot;
import dev.nano.ndidisplays.hoist.HoistGroups;
import dev.nano.ndidisplays.item.HoistRemoteItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → client: what the hoist remote's selector should show.
 *
 * Sent once when the pendant opens and again after every button, plus on the screen's own
 * slow refresh while it is open. Group membership and motor state both live server-side,
 * so this is the only way the remote knows anything.
 */
public record HoistGroupListPacket(int hand, String selected, boolean open, boolean estop,
                                   List<HoistGroupSnapshot> groups) {

    /** Guards against a pathological world where somebody made thousands of groups. */
    private static final int MAX_GROUPS = 256;

    public InteractionHand handEnum() {
        return hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    public static void open(ServerPlayer player, InteractionHand hand, String selected) {
        send(player, hand, selected, true);
    }

    public static void refresh(ServerPlayer player, InteractionHand hand, String selected) {
        send(player, hand, selected, false);
    }

    private static void send(ServerPlayer player, InteractionHand hand, String selected,
                             boolean open) {
        List<HoistGroupSnapshot> groups =
                HoistGroupSnapshot.collect(player.serverLevel(), player.blockPosition());
        if (groups.size() > MAX_GROUPS) {
            groups = groups.subList(0, MAX_GROUPS);
        }
        boolean estop = HoistRemoteItem.isEStop(player.getItemInHand(hand));
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HoistGroupListPacket(hand == InteractionHand.OFF_HAND ? 1 : 0,
                        HoistGroups.normalise(selected), open, estop, groups));
    }

    public static void encode(HoistGroupListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.hand);
        buf.writeUtf(msg.selected, HoistGroups.MAX_NAME_LENGTH);
        buf.writeBoolean(msg.open);
        buf.writeBoolean(msg.estop);
        buf.writeVarInt(msg.groups.size());
        for (HoistGroupSnapshot group : msg.groups) {
            group.write(buf);
        }
    }

    public static HoistGroupListPacket decode(FriendlyByteBuf buf) {
        int hand = buf.readVarInt();
        String selected = buf.readUtf(HoistGroups.MAX_NAME_LENGTH);
        boolean open = buf.readBoolean();
        boolean estop = buf.readBoolean();
        int count = Math.min(buf.readVarInt(), MAX_GROUPS);
        List<HoistGroupSnapshot> groups = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            groups.add(HoistGroupSnapshot.read(buf));
        }
        return new HoistGroupListPacket(hand, selected, open, estop, groups);
    }

    public static void handle(HoistGroupListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.nano.ndidisplays.client.ClientHooks.hoistRemoteState(msg)));
        ctx.get().setPacketHandled(true);
    }
}
