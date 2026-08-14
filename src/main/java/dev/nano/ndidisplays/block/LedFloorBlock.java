package dev.nano.ndidisplays.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * Walkable LED floor tile. FACING is the direction of the image's top (v=0).
 * Adjacent tiles of the same facing merge into one video rectangle, like a wall.
 */
public class LedFloorBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /** Slim walkable slab, 2 pixels tall — collision matches the visual cabinet. */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);

    public LedFloorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Image top points the way the player is looking, like a floor processor's orientation.
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof dev.nano.ndidisplays.item.NdiConfigCardItem) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof LedFloorBlockEntity clicked) {
                String source = dev.nano.ndidisplays.item.NdiConfigCardItem
                        .storedSource(player.getItemInHand(hand));
                for (BlockPos tilePos : FloorScanner.collectGroup(level, pos, clicked.getFacing(),
                        clicked.getPanelKind())) {
                    if (level.getBlockEntity(tilePos) instanceof LedFloorBlockEntity tile) {
                        tile.applyNdiCard(source);
                        BlockState tileState = level.getBlockState(tilePos);
                        level.sendBlockUpdated(tilePos, tileState, tileState, 3);
                    }
                }
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.ndidisplays.ndi_config_card.applied", source), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (DmxScreen.isTheatricalCard(player.getItemInHand(hand))) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof LedFloorBlockEntity clicked) {
                DmxScreen.applyTheatricalCard(level, pos, state, player,
                        player.getItemInHand(hand), clicked);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.nano.ndidisplays.client.ClientHooks.openFloorConfig(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LedFloorBlockEntity(pos, state);
    }
}
