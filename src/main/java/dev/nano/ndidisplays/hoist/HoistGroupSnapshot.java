package dev.nano.ndidisplays.hoist;

import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * One line on the remote's selector: a group, and what it is doing right now.
 *
 * The remote is held in the hand, not wired to a block entity, so it has nothing to read
 * state off. The server builds these instead and pushes them to whoever has the pendant
 * open. Everything here is a summary, because the operator is looking at four motors as
 * one truss — an individual motor's exact chain length belongs on that motor's own pendant.
 */
public record HoistGroupSnapshot(String name, int motors, int loaded, int status,
                                 float chain, int attached, int loadBlocks) {

    /** The unnamed selector position: every motor around the operator. */
    public static final String ALL = "";

    public HoistStatus statusEnum() {
        return HoistStatus.byOrdinal(status);
    }

    public boolean isAll() {
        return name.isEmpty();
    }

    // ------------------------------------------------------------------ building

    /** The whole selector: "all motors" first, then every named group. */
    public static List<HoistGroupSnapshot> collect(ServerLevel level, BlockPos centre) {
        List<HoistGroupSnapshot> out = new ArrayList<>();
        out.add(of(ALL, HoistScan.near(level, centre), -1));
        for (String group : HoistGroups.get(level).names()) {
            out.add(of(group, HoistScan.members(level, group),
                    HoistGroups.get(level).members(group).size()));
        }
        return out;
    }

    /**
     * Rolls a set of motors into one status.
     *
     * Worst case wins, the way a rig is read in a venue: one faulted motor means the rig is
     * faulted, and a truss is "going up" the moment any of its points is moving. Limits only
     * show once every motor agrees, otherwise a four-point truss would read "upper limit"
     * while three of its points still had travel left.
     */
    private static HoistGroupSnapshot of(String name, List<ChainHoistBlockEntity> motors,
                                         int knownMembers) {
        if (motors.isEmpty()) {
            return new HoistGroupSnapshot(name, Math.max(knownMembers, 0), 0,
                    HoistStatus.STOPPED.ordinal(), 0, 0, 0);
        }
        HoistStatus worst = null;
        float chainTotal = 0;
        int attached = 0;
        int blocks = 0;
        boolean allUpper = true;
        boolean allLower = true;
        for (ChainHoistBlockEntity hoist : motors) {
            HoistStatus status = hoist.getStatus();
            if (worst == null || severity(status) > severity(worst)) {
                worst = status;
            }
            allUpper &= status == HoistStatus.UPPER_LIMIT;
            allLower &= status == HoistStatus.LOWER_LIMIT;
            chainTotal += hoist.getChainLength();
            if (hoist.isAttached()) {
                attached++;
                if (hoist.isOwner()) {
                    blocks += hoist.getLoadBlocks();
                }
            }
        }
        if (worst == HoistStatus.UPPER_LIMIT && !allUpper) {
            worst = HoistStatus.STOPPED;
        } else if (worst == HoistStatus.LOWER_LIMIT && !allLower) {
            worst = HoistStatus.STOPPED;
        }
        return new HoistGroupSnapshot(name,
                Math.max(knownMembers, motors.size()), motors.size(),
                worst.ordinal(), chainTotal / motors.size(), attached, blocks);
    }

    /** How loudly a status should shout over the others when several motors disagree. */
    private static int severity(HoistStatus status) {
        return switch (status) {
            case STOPPED -> 0;
            case UPPER_LIMIT, LOWER_LIMIT -> 1;
            case MOVING_UP, MOVING_DOWN -> 2;
            case OBSTRUCTED -> 3;
            case FAULT -> 4;
        };
    }

    // ------------------------------------------------------------------ wire

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, HoistGroups.MAX_NAME_LENGTH);
        buf.writeVarInt(motors);
        buf.writeVarInt(loaded);
        buf.writeVarInt(status);
        buf.writeFloat(chain);
        buf.writeVarInt(attached);
        buf.writeVarInt(loadBlocks);
    }

    public static HoistGroupSnapshot read(FriendlyByteBuf buf) {
        return new HoistGroupSnapshot(
                buf.readUtf(HoistGroups.MAX_NAME_LENGTH),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readVarInt());
    }
}
