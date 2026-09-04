package dev.nano.ndidisplays.compat.sef;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.hoist.HoistConfig;
import dev.nano.ndidisplays.hoist.RigStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional Stage Entertainment Furnitures integration: keeps audio routing pointing at
 * the speakers after they have been flown.
 *
 * The speakers themselves need no help — their block entities travel with their full NBT,
 * so cabinet angles, array geometry and per-box settings all survive. What does not
 * survive is the other end of the wire: an SEF mixer or scene processor sitting on the
 * floor holds its outputs as a list of speaker <em>positions</em>, and once a PA hang has
 * flown four metres those positions point at empty air.
 *
 * The fix is deliberately blunt and version-proof. This class never imports an SEF type
 * and never reflects into one; it rewrites the position compounds inside nearby SEF block
 * entities' own saved NBT, which is a stable format in a way that SEF's obfuscated class
 * names are not. If SEF is absent the whole thing is a no-op and flying speakers still
 * works — only the routing would need re-patching by hand.
 */
public final class SefHoistCompat {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String SEF = "sef";

    /** How far from the load to look for consoles that reference it. */
    private static final double SEARCH_RADIUS = 96.0;

    public static final boolean LOADED = ModList.get().isLoaded(SEF);

    private SefHoistCompat() {
    }

    /**
     * Re-points SEF routing at a load that has just landed somewhere new.
     *
     * @param structure the load, whose entries still carry the offsets it was captured with
     * @param newOrigin where it came down
     */
    public static void onRigLanded(ServerLevel level, RigStructure structure, BlockPos newOrigin) {
        if (!LOADED || structure.origin().equals(newOrigin) || structure.size() == 0) {
            return;
        }

        // Only positions that actually moved, and only ones SEF might care about.
        Map<BlockPos, BlockPos> moved = new HashMap<>();
        for (RigStructure.Entry entry : structure.entries()) {
            if (!isSefBlock(entry.state())) {
                continue;
            }
            moved.put(structure.origin().offset(entry.offset()),
                    newOrigin.offset(entry.offset()));
        }
        if (moved.isEmpty()) {
            return;
        }

        try {
            remapNearbyConsoles(level, newOrigin, moved);
        } catch (RuntimeException e) {
            // Routing is a convenience. A surprise here must never stop a load landing.
            LOG.warn("[ndidisplays] SEF routing remap failed after a hoist move", e);
        }
    }

    private static boolean isSefBlock(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && SEF.equals(id.getNamespace());
    }

    /**
     * Walks the SEF block entities around the landing site and rewrites any reference to
     * a speaker that moved. Consoles inside the load are skipped: they travelled with it,
     * so anything they point at that also travelled is still correct relative to them —
     * and anything they point at that did not move never changed address.
     */
    private static void remapNearbyConsoles(ServerLevel level, BlockPos newOrigin,
                                            Map<BlockPos, BlockPos> moved) {
        double reach = SEARCH_RADIUS + HoistConfig.maxChainLength();
        AABB area = new AABB(newOrigin).inflate(reach);

        int minChunkX = (int) Math.floor(area.minX) >> 4;
        int maxChunkX = (int) Math.floor(area.maxX) >> 4;
        int minChunkZ = (int) Math.floor(area.minZ) >> 4;
        int maxChunkZ = (int) Math.floor(area.maxZ) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (moved.containsKey(be.getBlockPos()) || !isSefBlock(be.getBlockState())) {
                        continue;
                    }
                    remapBlockEntity(level, be, moved);
                }
            }
        }
    }

    private static void remapBlockEntity(ServerLevel level, BlockEntity be,
                                         Map<BlockPos, BlockPos> moved) {
        CompoundTag tag = be.saveWithFullMetadata();
        if (!rewritePositions(tag, moved, 0)) {
            return;
        }
        be.load(tag);
        be.setChanged();
        BlockState state = be.getBlockState();
        level.sendBlockUpdated(be.getBlockPos(), state, state, 3);
    }

    /**
     * Rewrites every {@code {X,Y,Z}} compound in {@code tag} that names a block which
     * moved. SEF writes block positions with {@code NbtUtils.writeBlockPos}, so this one
     * shape covers mixer outputs, scene processor patches and channel strips alike.
     *
     * @return true if anything changed
     */
    private static boolean rewritePositions(CompoundTag tag, Map<BlockPos, BlockPos> moved,
                                            int depth) {
        if (depth > 16) {
            return false;
        }
        boolean changed = false;

        if (isPositionTag(tag)) {
            BlockPos to = moved.get(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")));
            if (to != null) {
                tag.putInt("X", to.getX());
                tag.putInt("Y", to.getY());
                tag.putInt("Z", to.getZ());
                return true;
            }
        }

        for (String key : tag.getAllKeys()) {
            // The block entity's own coordinates are metadata, not a reference.
            if (depth == 0 && ("x".equals(key) || "y".equals(key) || "z".equals(key))) {
                continue;
            }
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                changed |= rewritePositions(compound, moved, depth + 1);
            } else if (child instanceof ListTag list && list.getElementType() == Tag.TAG_COMPOUND) {
                for (int i = 0; i < list.size(); i++) {
                    changed |= rewritePositions(list.getCompound(i), moved, depth + 1);
                }
            }
        }
        return changed;
    }

    /** A compound that is exactly a written {@code BlockPos} and nothing else. */
    private static boolean isPositionTag(CompoundTag tag) {
        return tag.size() == 3
                && tag.contains("X", Tag.TAG_INT)
                && tag.contains("Y", Tag.TAG_INT)
                && tag.contains("Z", Tag.TAG_INT);
    }
}
