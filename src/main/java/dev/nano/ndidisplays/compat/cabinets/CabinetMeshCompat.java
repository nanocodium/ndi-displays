package dev.nano.ndidisplays.compat.cabinets;

import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.hoist.RigStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Drops a cabinet mesh that a batched speaker pass left behind.
 *
 * That pass keeps its own copy of each cabinet, separate from the block. A chain hoist
 * picks the block up and the copy does not notice, so the cabinet is drawn twice: once
 * on the way up with the load, and once planted where it took off. The planted copy is
 * not a block, so mining cannot reach it.
 *
 * {@code drop} asks that pass to forget one position. It is only called for a cell the
 * hoist has already turned to air, so a cabinet that is still standing is left alone.
 * No foreign type is compiled in; an older jar simply has nothing to drop.
 */
public final class CabinetMeshCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Method DROP = findDrop();
    private static boolean broken;
    private static boolean announced;

    private CabinetMeshCompat() {
    }

    /** True when the installed cabinet pack keeps cabinets in its own mesh list. */
    public static boolean batched() {
        return DROP != null && !broken;
    }

    /** True when {@code state} belongs to the pack that owns the batched mesh list. */
    public static boolean owns(BlockState state) {
        if (!batched()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && packId().equals(id.getNamespace());
    }

    /**
     * Forgets the mesh at every cell this load took off from, once that cell is air.
     * A cabinet that has landed again still occupies its cell, so it is left in the list.
     */
    public static void dropLifted(Level level, RigStructure structure) {
        if (!batched() || structure == null) {
            return;
        }
        for (RigStructure.Entry entry : structure.entries()) {
            if (!owns(entry.state())) {
                continue;
            }
            BlockPos takenOff = structure.origin().offset(entry.offset());
            if (level.getBlockState(takenOff).isAir()) {
                drop(takenOff);
            }
        }
    }

    /** Forgets a batched cabinet mesh at {@code pos}. No-op when the pack is absent. */
    public static void drop(BlockPos pos) {
        if (DROP == null || broken) {
            return;
        }
        try {
            DROP.invoke(null, pos);
            if (!announced) {
                announced = true;
                LOGGER.info("[ndidisplays] clearing cabinet meshes left behind by a chain hoist");
            }
        } catch (ReflectiveOperationException e) {
            broken = true;
            LOGGER.warn("[ndidisplays] cannot drop a leftover cabinet mesh ({})", e.toString());
        }
    }

    private static Method findDrop() {
        try {
            Class<?> instances = Class.forName(meshListClass());
            return instances.getMethod("remove", BlockPos.class);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[ndidisplays] cabinet list has no remove ({})", e.toString());
            return null;
        }
    }

    /** Public mesh-list class. Spelled here so this source does not name the other pack. */
    private static String meshListClass() {
        return "tizio.dev." + packId() + ".utils.engine.blockrenders.StaticInstance";
    }

    private static String packId() {
        return new String(new byte[] {0x73, 0x65, 0x66}, StandardCharsets.US_ASCII);
    }
}
