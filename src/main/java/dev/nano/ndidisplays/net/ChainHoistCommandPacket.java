package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import dev.nano.ndidisplays.hoist.HoistGroups;
import dev.nano.ndidisplays.hoist.HoistStatus;
import dev.nano.ndidisplays.hoist.ScanResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client → server: one press on a chain hoist pendant.
 *
 * Everything the operator can do goes through here — configuration, the direction keys,
 * a target height, attaching and detaching, and the same set again aimed at a whole
 * group. The server re-derives every limit itself; the packet is a request, never a
 * statement of where the load now is.
 */
public record ChainHoistCommandPacket(BlockPos pos, Action action, float target,
                                      float minChain, float maxChain, float speed,
                                      String group) {

    public enum Action {
        /** Limits, speed and group name only. */
        CONFIGURE,
        UP,
        DOWN,
        STOP,
        /** Run to {@link #target} metres of chain. */
        GOTO,
        /** Drop the chain, find the load, pick it up. */
        ATTACH,
        /** Set the load down and let go. */
        DETACH,
        GROUP_UP,
        GROUP_DOWN,
        GROUP_STOP,
        GROUP_GOTO;

        boolean isGroup() {
            return ordinal() >= GROUP_UP.ordinal();
        }

        static Action byOrdinal(int ordinal) {
            Action[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : STOP;
        }
    }

    public static ChainHoistCommandPacket of(BlockPos pos, Action action) {
        return new ChainHoistCommandPacket(pos, action, 0, 0, 0, 0, "");
    }

    public static ChainHoistCommandPacket goTo(BlockPos pos, Action action, float target) {
        return new ChainHoistCommandPacket(pos, action, target, 0, 0, 0, "");
    }

    public static ChainHoistCommandPacket configure(BlockPos pos, float minChain, float maxChain,
                                                    float speed, String group) {
        return new ChainHoistCommandPacket(pos, Action.CONFIGURE, 0,
                minChain, maxChain, speed, group);
    }

    public static void encode(ChainHoistCommandPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.action.ordinal());
        buf.writeFloat(msg.target);
        buf.writeFloat(msg.minChain);
        buf.writeFloat(msg.maxChain);
        buf.writeFloat(msg.speed);
        buf.writeUtf(msg.group, HoistGroups.MAX_NAME_LENGTH);
    }

    public static ChainHoistCommandPacket decode(FriendlyByteBuf buf) {
        return new ChainHoistCommandPacket(
                buf.readBlockPos(),
                Action.byOrdinal(buf.readVarInt()),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readUtf(HoistGroups.MAX_NAME_LENGTH));
    }

    public static void handle(ChainHoistCommandPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !NetworkHandler.mayConfigure(player, msg.pos)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!(level.getBlockEntity(msg.pos) instanceof ChainHoistBlockEntity hoist)) {
                return;
            }

            if (msg.action.isGroup()) {
                runGroup(level, hoist, msg);
            } else {
                run(hoist, msg);
            }
            if (msg.action == Action.ATTACH) {
                tell(player, hoist);
            } else if (msg.action == Action.DETACH) {
                if (hoist.isAttached() && hoist.getLastFailure() != ScanResult.Failure.NONE) {
                    player.displayClientMessage(
                            Component.translatable(hoist.getLastFailure().translationKey()), true);
                } else if (!hoist.isAttached()) {
                    player.displayClientMessage(
                            Component.translatable("gui.ndidisplays.hoist.unhooked"), true);
                }
            } else if (hoist.getStatus() == HoistStatus.FAULT
                    && hoist.getLastFailure() != ScanResult.Failure.NONE) {
                player.displayClientMessage(
                        Component.translatable(hoist.getLastFailure().translationKey()), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void run(ChainHoistBlockEntity hoist, ChainHoistCommandPacket msg) {
        switch (msg.action) {
            case CONFIGURE ->
                    hoist.applyConfig(msg.minChain, msg.maxChain, msg.speed, msg.group);
            case UP -> hoist.commandUp();
            case DOWN -> hoist.commandDown();
            case STOP -> hoist.commandStop();
            case GOTO -> hoist.commandGoto(msg.target);
            case ATTACH -> hoist.commandAttach();
            case DETACH -> hoist.commandDetach();
            default -> {
            }
        }
    }

    /**
     * Runs the command on every motor of the clicked hoist's group.
     *
     * UP and DOWN move the whole group by one shared amount of chain, so whatever attitude
     * the hang is in is the attitude it keeps — that is the difference between the group
     * keys and the motor keys right above them. GOTO is deliberately absolute: asking for a
     * trim height by number is asking for a level rig at that height.
     */
    private static void runGroup(ServerLevel level, ChainHoistBlockEntity clicked,
                                 ChainHoistCommandPacket msg) {
        String group = clicked.getGroup();
        if (group.isEmpty()) {
            run(clicked, ungrouped(msg));
            return;
        }
        List<ChainHoistBlockEntity> motors = new ArrayList<>();
        for (BlockPos member : HoistGroups.get(level).members(group)) {
            if (level.isLoaded(member)
                    && level.getBlockEntity(member) instanceof ChainHoistBlockEntity motor) {
                motors.add(motor);
            }
        }
        if (motors.isEmpty()) {
            return;
        }
        switch (msg.action) {
            case GROUP_UP -> ChainHoistBlockEntity.groupMove(motors, true);
            case GROUP_DOWN -> ChainHoistBlockEntity.groupMove(motors, false);
            default -> motors.forEach(motor -> run(motor, ungrouped(msg)));
        }
    }

    private static void tell(ServerPlayer player, ChainHoistBlockEntity hoist) {
        if (hoist.getLastFailure() != ScanResult.Failure.NONE
                && hoist.getStatus() == HoistStatus.FAULT) {
            player.displayClientMessage(
                    Component.translatable(hoist.getLastFailure().translationKey()), true);
        } else if (hoist.isAttached()) {
            player.displayClientMessage(Component.translatable(
                    "gui.ndidisplays.hoist.attached",
                    hoist.getLoadBlocks()), true);
        }
    }
    private static ChainHoistCommandPacket ungrouped(ChainHoistCommandPacket msg) {
        Action single = switch (msg.action) {
            case GROUP_UP -> Action.UP;
            case GROUP_DOWN -> Action.DOWN;
            case GROUP_STOP -> Action.STOP;
            case GROUP_GOTO -> Action.GOTO;
            default -> msg.action;
        };
        return new ChainHoistCommandPacket(msg.pos, single, msg.target,
                msg.minChain, msg.maxChain, msg.speed, msg.group);
    }
}
