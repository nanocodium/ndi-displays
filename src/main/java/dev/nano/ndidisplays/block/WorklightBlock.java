package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.item.WorklightRemoteItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Halogen work light: a flood in a yoke that hangs from the block above and aims where it
 * was placed towards. Right-click switches it, sneak + right-click tilts the head one notch,
 * a {@link WorklightRemoteItem} patches it into a group.
 *
 * The whole lamp is drawn by its block entity renderer; the block model only carries the
 * particle texture. Light level rides on {@link #LIT} so the game lights the room the way a
 * real 2 kW halogen does the moment the switch is thrown.
 */
public class WorklightBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** Yoke under the ceiling, head hanging below it. */
    private static final VoxelShape SHAPE = Block.box(2, 7, 4, 14, 16, 12);

    public WorklightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    /** Aims away from the player: you stand in the house and point it at the stage. */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof WorklightRemoteItem) {
            // The remote patches lamps into its group by touch: walk the rig and tap each one.
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof WorklightBlockEntity lamp) {
                String group = WorklightRemoteItem.group(held);
                if (group.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("gui.ndidisplays.worklight.no_group_on_remote"), true);
                } else {
                    lamp.setGroup(group);
                    player.displayClientMessage(Component.translatable(
                            "gui.ndidisplays.worklight.group_set", Component.literal(group)), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WorklightBlockEntity lamp) {
            if (player.isShiftKeyDown()) {
                lamp.stepTilt();
                player.displayClientMessage(Component.translatable(
                        "gui.ndidisplays.worklight.tilt", Math.round(lamp.getTilt())), true);
            } else {
                lamp.setLit(!state.getValue(LIT));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof WorklightBlockEntity lamp) {
            lamp.onRemoved();
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorklightBlockEntity(pos, state);
    }
}
