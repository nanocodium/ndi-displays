package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.block.LedCornerBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Outer and inner corner cabinets are the same block ({@code convex} on the state).
 * Two items so the creative tab and recipes show both shapes without sneak-place.
 */
public class LedCornerItem extends BlockItem {

    private final boolean convex;

    public LedCornerItem(Block block, Properties properties, boolean convex) {
        super(block, properties);
        this.convex = convex;
    }

    public boolean convex() {
        return convex;
    }

    @Override
    @Nullable
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null : state.setValue(LedCornerBlock.CONVEX, convex);
    }
}
