package dev.nano.ndidisplays.hoist;

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
 * Named motor groups, saved with the level.
 *
 * Concert rigs are operated in groups, not one motor at a time: "main truss" is four
 * hoists that go up together, and asking an operator to click each one is how a truss
 * ends up hanging by three points. A group here is just a name and the motors that
 * answer to it — the actual synchronisation is the rig's job, since motors on one
 * structure are already rigid.
 *
 * Membership lives on the motors too. This index exists so a group command does not
 * have to search the world for its members.
 */
public class HoistGroups extends SavedData {

    private static final String NAME = "ndidisplays_hoist_groups";

    public static final int MAX_NAME_LENGTH = 32;

    private final Map<String, Set<BlockPos>> groups = new LinkedHashMap<>();

    public static HoistGroups get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(HoistGroups::load, HoistGroups::new, NAME);
    }

    /** Normalises operator input so "Main Truss" and "main truss " are one group. */
    public static String normalise(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
        }
        return trimmed;
    }

    public void join(String group, BlockPos motor) {
        String key = normalise(group);
        if (key.isEmpty()) {
            return;
        }
        groups.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(motor.immutable());
        setDirty();
    }

    /** Removes a motor from every group — used when its group changes or it is mined. */
    public void leaveAll(BlockPos motor) {
        groups.values().removeIf(members -> {
            members.remove(motor);
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

    public HoistGroups() {
    }

    public static HoistGroups load(CompoundTag tag) {
        HoistGroups data = new HoistGroups();
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
