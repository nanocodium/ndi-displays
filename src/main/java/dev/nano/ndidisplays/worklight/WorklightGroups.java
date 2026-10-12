package dev.nano.ndidisplays.worklight;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Named work light groups, saved with the level.
 *
 * Work lights are switched by area, never one lamp at a time: "stage left", "FOH", "load-in"
 * are each a handful of halogen floods that go on together when the show lights go down. A
 * group is a name and the lamps that answer to it. Membership lives on the lamps too; this
 * index exists so a remote does not have to search the world for its members.
 */
public class WorklightGroups extends SavedData {

    private static final String NAME = "ndidisplays_worklight_groups";

    public static final int MAX_NAME_LENGTH = 32;

    private final Map<String, Set<BlockPos>> groups = new LinkedHashMap<>();

    public static WorklightGroups get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WorklightGroups::load, WorklightGroups::new, NAME);
    }

    /** Normalises operator input so "Stage Left" and "stage left " are one group. */
    public static String normalise(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
        }
        return trimmed;
    }

    public void join(String group, BlockPos lamp) {
        String key = normalise(group);
        if (key.isEmpty()) {
            return;
        }
        groups.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(lamp.immutable());
        setDirty();
    }

    /** Removes a lamp from every group — when its group changes or it is mined. */
    public void leaveAll(BlockPos lamp) {
        groups.values().removeIf(members -> {
            members.remove(lamp);
            return members.isEmpty();
        });
        setDirty();
    }

    public Set<BlockPos> members(String group) {
        Set<BlockPos> members = groups.get(normalise(group));
        return members == null ? Set.of() : members;
    }

    public List<String> names() {
        return new ArrayList<>(groups.keySet());
    }

    // ------------------------------------------------------------------ persistence

    public WorklightGroups() {
    }

    public static WorklightGroups load(CompoundTag tag) {
        WorklightGroups data = new WorklightGroups();
        for (String key : tag.getAllKeys()) {
            Set<BlockPos> members = new LinkedHashSet<>();
            for (long packed : tag.getLongArray(key)) {
                members.add(BlockPos.of(packed));
            }
            if (!members.isEmpty()) {
                data.groups.put(key, members);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<String, Set<BlockPos>> entry : groups.entrySet()) {
            long[] packed = new long[entry.getValue().size()];
            int i = 0;
            for (BlockPos pos : entry.getValue()) {
                packed[i++] = pos.asLong();
            }
            tag.putLongArray(entry.getKey(), packed);
        }
        return tag;
    }
}
