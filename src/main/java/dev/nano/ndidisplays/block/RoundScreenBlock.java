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
 * Mount block of the circular LED screen. The block itself is a compact hub; the
 * renderer draws the video disc around it at the configured radius, facing the
 * block's FACING direction. Right-click opens the config (source, pitch, brightness,
 * pattern, radius); the NDI configuration card applies its source directly.
 */
public class RoundScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /** Compact centre hub the disc is built around. */
    private static final VoxelShape SHAPE = Block.box(4, 4, 4, 12, 12, 12);

    public RoundScreenBlock(Properties properties) {
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
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        // NDI configuration card: switch the disc to the card's video source.
        if (player.getItemInHand(hand).getItem() instanceof dev.nano.ndidisplays.item.NdiConfigCardItem) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof RoundScreenBlockEntity screen) {
                String source = dev.nano.ndidisplays.item.NdiConfigCardItem
                        .storedSource(player.getItemInHand(hand));
                screen.applyNdiCard(source);
                level.sendBlockUpdated(pos, state, state, 3);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.ndidisplays.ndi_config_card.applied", source), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // Theatrical configuration card: patch the disc as a 2ch fixture (dimmer + source).
        if (DmxScreen.isTheatricalCard(player.getItemInHand(hand))) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof RoundScreenBlockEntity screen) {
                DmxScreen.applyTheatricalCard(level, pos, state, player,
                        player.getItemInHand(hand), screen);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.nano.ndidisplays.client.ClientHooks.openRoundScreenConfig(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RoundScreenBlockEntity(pos, state);
    }
}
