package dev.nano.ndidisplays.worklight;

import dev.nano.ndidisplays.block.WorklightBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the lamps a work light remote is talking to: a named group's members through the
 * saved index, or, for an unnamed remote, every work light in the loaded chunks around the
 * operator. The radius is the point — the remote switches the lights of the room you stand
 * in, not every lamp in the world.
 */
public final class WorklightScan {

    /** Chunks either side of the operator swept for an unnamed remote. */
    public static final int SWEEP_CHUNKS = 6;

    private WorklightScan() {
    }

    public static List<WorklightBlockEntity> resolve(ServerLevel level, String group, BlockPos centre) {
        String key = WorklightGroups.normalise(group);
        return key.isEmpty() ? near(level, centre) : members(level, key);
    }

    /** Loaded members of one named group. Unloaded ones are skipped, never dropped. */
    public static List<WorklightBlockEntity> members(ServerLevel level, String group) {
        List<WorklightBlockEntity> out = new ArrayList<>();
        for (BlockPos pos : WorklightGroups.get(level).members(group)) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WorklightBlockEntity lamp) {
                out.add(lamp);
            }
        }
        return out;
    }

    /** Every work light in the loaded chunks around a point. */
    public static List<WorklightBlockEntity> near(ServerLevel level, BlockPos centre) {
        List<WorklightBlockEntity> out = new ArrayList<>();
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        for (int x = cx - SWEEP_CHUNKS; x <= cx + SWEEP_CHUNKS; x++) {
            for (int z = cz - SWEEP_CHUNKS; z <= cz + SWEEP_CHUNKS; z++) {
                ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk loaded)) {
                    continue;
                }
                loaded.getBlockEntities().values().forEach(be -> {
                    if (be instanceof WorklightBlockEntity lamp) {
                        out.add(lamp);
                    }
                });
            }
        }
        return out;
    }
}
