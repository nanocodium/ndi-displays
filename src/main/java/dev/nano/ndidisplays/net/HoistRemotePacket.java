package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import dev.nano.ndidisplays.hoist.HoistGroups;
import dev.nano.ndidisplays.hoist.HoistScan;
import dev.nano.ndidisplays.item.HoistRemoteItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client → server: one press on the hoist radio remote.
 *
 * The remote addresses a group by name rather than a motor by position, so unlike the
 * pendant on the block there is nothing here the server can trust — it looks the group up
 * itself, filters to the motors this player is actually allowed to run, and applies the
 * command to those. A remote that could reach a motor the player cannot see or build near
 * would be a remote that moves other people's builds.
 */
public record HoistRemotePacket(Action action, String group, int hand) {

    public enum Action {
        /** Refresh the selector without touching anything. */
        POLL,
        /** Store a new group on the remote. */
        SELECT,
        UP,
        DOWN,
        STOP,
        /** Mushroom in: stop every motor within reach and refuse further travel. */
        ESTOP,
        /** Twist-release the mushroom so the remote will run again. */
        RELEASE_ESTOP,
        ATTACH,
        DETACH;

        static Action byOrdinal(int ordinal) {
            Action[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : POLL;
        }
    }

    public static HoistRemotePacket of(Action action, String group, InteractionHand hand) {
        return new HoistRemotePacket(action, HoistGroups.normalise(group),
                hand == InteractionHand.OFF_HAND ? 1 : 0);
    }

    public InteractionHand handEnum() {
        return hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    public static void encode(HoistRemotePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action.ordinal());
        buf.writeUtf(msg.group, HoistGroups.MAX_NAME_LENGTH);
        buf.writeVarInt(msg.hand);
    }

    public static HoistRemotePacket decode(FriendlyByteBuf buf) {
        return new HoistRemotePacket(
                Action.byOrdinal(buf.readVarInt()),
                buf.readUtf(HoistGroups.MAX_NAME_LENGTH),
                buf.readVarInt());
    }

    public static void handle(HoistRemotePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            InteractionHand hand = msg.handEnum();
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof HoistRemoteItem)) {
                // The remote left the hand between the press and the packet — a hotbar
                // scroll, a death, a creative-menu swap. Nothing to run.
                return;
            }
            ServerLevel level = player.serverLevel();

            if (msg.action == Action.SELECT) {
                HoistRemoteItem.setGroup(stack, msg.group);
            } else if (msg.action == Action.ESTOP) {
                HoistRemoteItem.setEStop(stack, true);
                apply(level, player, msg);
            } else if (msg.action == Action.RELEASE_ESTOP) {
                HoistRemoteItem.setEStop(stack, false);
            } else if (msg.action != Action.POLL) {
                if (HoistRemoteItem.isEStop(stack) && blocksTravel(msg.action)) {
                    player.displayClientMessage(
                            Component.translatable("gui.ndidisplays.remote.estop_blocked"), true);
                } else {
                    apply(level, player, msg);
                }
            }
            HoistGroupListPacket.refresh(player, hand, HoistRemoteItem.selectedGroup(stack));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void apply(ServerLevel level, ServerPlayer player, HoistRemotePacket msg) {
        List<ChainHoistBlockEntity> motors = reachable(level, player,
                msg.action == Action.ESTOP
                        ? HoistScan.everything(level, player.blockPosition())
                        : HoistScan.resolve(level, msg.group, player.blockPosition()));

        if (motors.isEmpty()) {
            if (msg.action != Action.ESTOP) {
                player.displayClientMessage(
                        Component.translatable("gui.ndidisplays.remote.no_motors"), true);
            }
            return;
        }

        // UP and DOWN move the whole selection by one shared amount of chain, so a hang
        // that was level stays level and a hang that was raked keeps its rake. Running
        // each motor to its own limit instead would quietly re-trim the rig every press.
        switch (msg.action) {
            case UP -> ChainHoistBlockEntity.groupMove(motors, true);
            case DOWN -> ChainHoistBlockEntity.groupMove(motors, false);
            default -> {
                for (ChainHoistBlockEntity motor : motors) {
                    switch (msg.action) {
                        case STOP, ESTOP -> motor.commandStop();
                        case ATTACH -> motor.commandAttach();
                        case DETACH -> motor.commandDetach();
                        default -> {
                        }
                    }
                }
            }
        }

        if (msg.action == Action.ESTOP) {
            player.displayClientMessage(Component.translatable(
                    "gui.ndidisplays.remote.estop_done", motors.size()), true);
        }
    }

    /** Travel keys: the latched mushroom must refuse these, not just look red. */
    private static boolean blocksTravel(Action action) {
        return action == Action.UP || action == Action.DOWN
                || action == Action.ATTACH || action == Action.DETACH;
    }

    /** Drops motors this player may not operate, rather than refusing the whole press. */
    private static List<ChainHoistBlockEntity> reachable(ServerLevel level, ServerPlayer player,
                                                         List<ChainHoistBlockEntity> motors) {
        List<ChainHoistBlockEntity> out = new ArrayList<>(motors.size());
        for (ChainHoistBlockEntity motor : motors) {
            if (motor.getLevel() == level
                    && NetworkHandler.mayOperateRemote(player, motor.getBlockPos())) {
                out.add(motor);
            }
        }
        return out;
    }
}
