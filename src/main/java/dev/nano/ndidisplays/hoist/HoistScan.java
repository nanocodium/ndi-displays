package dev.nano.ndidisplays.hoist;

import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the motors a radio remote is talking to.
 *
 * A named group is looked up in {@link HoistGroups}, which knows exactly where its members
 * are. The unnamed group — the "all motors" position on the selector — has no index, so it
 * is resolved by sweeping the loaded chunks around the operator. That radius is not a
 * limitation so much as the point: a remote is meant to run the rig you are standing under,
 * not every motor in the world.
 */
public final class HoistScan {

    /** Chunks either side of the operator swept for the unnamed group and the e-stop. */
    public static final int SWEEP_CHUNKS = 8;

    private HoistScan() {
    }

    /** Motors the remote should command: a named group's members, or everything nearby. */
    public static List<ChainHoistBlockEntity> resolve(ServerLevel level, String group,
                                                      BlockPos centre) {
        String key = HoistGroups.normalise(group);
        return key.isEmpty() ? near(level, centre) : members(level, key);
    }

    /** Loaded members of one named group. Unloaded ones are skipped, never dropped. */
    public static List<ChainHoistBlockEntity> members(ServerLevel level, String group) {
        List<ChainHoistBlockEntity> out = new ArrayList<>();
        for (BlockPos pos : HoistGroups.get(level).members(group)) {
            if (level.isLoaded(pos)
                    && level.getBlockEntity(pos) instanceof ChainHoistBlockEntity hoist) {
                out.add(hoist);
            }
        }
        return out;
    }

    /** Every hoist in the loaded chunks around a point. */
    public static List<ChainHoistBlockEntity> near(ServerLevel level, BlockPos centre) {
        List<ChainHoistBlockEntity> out = new ArrayList<>();
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        for (int x = cx - SWEEP_CHUNKS; x <= cx + SWEEP_CHUNKS; x++) {
            for (int z = cz - SWEEP_CHUNKS; z <= cz + SWEEP_CHUNKS; z++) {
                ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk loaded)) {
                    continue;
                }
                loaded.getBlockEntities().values().forEach(be -> {
                    if (be instanceof ChainHoistBlockEntity hoist) {
                        out.add(hoist);
                    }
                });
            }
        }
        return out;
    }

    /**
     * Everything an emergency stop has to reach: the motors around the operator plus every
     * loaded member of every group, in case a rig is being run from the far side of a room.
     */
    public static List<ChainHoistBlockEntity> everything(ServerLevel level, BlockPos centre) {
        Set<BlockPos> seen = new LinkedHashSet<>();
        List<ChainHoistBlockEntity> out = new ArrayList<>();
        for (ChainHoistBlockEntity hoist : near(level, centre)) {
            if (seen.add(hoist.getBlockPos())) {
                out.add(hoist);
            }
        }
        for (String group : HoistGroups.get(level).names()) {
            for (ChainHoistBlockEntity hoist : members(level, group)) {
                if (seen.add(hoist.getBlockPos())) {
                    out.add(hoist);
                }
            }
        }
        return out;
    }
}
