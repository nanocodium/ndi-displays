package dev.nano.ndidisplays.hoist;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Block tags that bound what a chain hoist is allowed to pick up. */
public final class HoistTags {

    /**
     * Blocks that must never travel: bedrock, portals, command blocks, and the hoists
     * and winches themselves. A hoist is machinery bolted to the grid, not cargo.
     */
    public static final TagKey<Block> IMMOVABLE = TagKey.create(Registries.BLOCK,
            new ResourceLocation(NdiDisplays.MODID, "hoist_immovable"));

    /**
     * Terrain. The scanner refuses to enter these at all, which is the single rule that
     * keeps a hoist stuck to a hillside from trying to fly the hillside.
     */
    public static final TagKey<Block> WORLD = TagKey.create(Registries.BLOCK,
            new ResourceLocation(NdiDisplays.MODID, "hoist_world"));

    private HoistTags() {
    }
}
