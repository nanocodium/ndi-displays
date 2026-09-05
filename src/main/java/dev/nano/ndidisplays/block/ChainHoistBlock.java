package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.hoist.HoistGroups;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * A stage chain hoist, clamped under a roof grid or a truss.
 *
 * This is the rigging motor, not the LED winch next door: it drops a real chain onto a
 * real structure and flies it. Right-click opens the pendant; an NDI configuration card
 * assigns the motor to a group so a whole truss runs on one command.
 */
public class ChainHoistBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /** Clamp plate, motor housing, load-wheel throat and chain bag. The chain itself is rendered, not collided. */
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(4, 15, 4, 12, 16, 12),
            Block.box(3.5, 5, 3.5, 12.5, 14, 12.5),
            Block.box(6.5, 4, 6.5, 9.5, 5, 9.5),
            Block.box(10, 0, 5, 15.5, 5.5, 11));

    public ChainHoistBlock(Properties properties) {
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);

        // Sneak with a block in hand places against the motor, like a chest. Without
        // this the pendant eats every right-click and a truss in hand can never snap
        // onto the hoist.
        if (player.isShiftKeyDown() && held.getItem() instanceof net.minecraft.world.item.BlockItem) {
            return InteractionResult.PASS;
        }

        // The radio remote patches motors into its selected group by touch: walk the grid
        // and tap each hoist. A remote sitting on "all motors" has no group to give, so it
        // takes one instead — point it at a motor already in a group to tune to that group.
        if (held.getItem() instanceof dev.nano.ndidisplays.item.HoistRemoteItem) {
            if (!level.isClientSide
                    && level.getBlockEntity(pos) instanceof ChainHoistBlockEntity hoist) {
                String remoteGroup = dev.nano.ndidisplays.item.HoistRemoteItem.selectedGroup(held);
                if (remoteGroup.isEmpty()) {
                    dev.nano.ndidisplays.item.HoistRemoteItem.setGroup(held, hoist.getGroup());
                    player.displayClientMessage(Component.translatable(
                            "gui.ndidisplays.remote.tuned",
                            hoist.getGroup().isEmpty()
                                    ? Component.translatable("gui.ndidisplays.hoist.no_group")
                                    : Component.literal(hoist.getGroup())), true);
                } else {
                    hoist.applyConfig(hoist.getMinChain(), hoist.getMaxChain(), hoist.getSpeed(),
                            remoteGroup);
                    player.displayClientMessage(Component.translatable(
                            "gui.ndidisplays.hoist.group_set",
                            Component.literal(remoteGroup)), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // An NDI card carrying a group name patches the motor into that group, so a rig
        // is built the way a rigger builds one: walk the grid, tap each motor.
        if (held.getItem() instanceof dev.nano.ndidisplays.item.NdiConfigCardItem) {
            if (!level.isClientSide
                    && level.getBlockEntity(pos) instanceof ChainHoistBlockEntity hoist) {
                String group = HoistGroups.normalise(
                        dev.nano.ndidisplays.item.NdiConfigCardItem.storedSource(held));
                hoist.applyConfig(hoist.getMinChain(), hoist.getMaxChain(), hoist.getSpeed(), group);
                player.displayClientMessage(Component.translatable(
                        "gui.ndidisplays.hoist.group_set",
                        group.isEmpty() ? Component.translatable("gui.ndidisplays.hoist.no_group")
                                : Component.literal(group)), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.nano.ndidisplays.client.ClientHooks.openChainHoistConfig(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Mining a motor must never take its load with it.
     *
     * The block entity hands the rig to another motor if there is one, and lands the load
     * if there is not. Both happen before the block entity is discarded, while it still
     * knows what it was carrying.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                         boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof ChainHoistBlockEntity hoist) {
                hoist.onBroken();
            }
            if (level instanceof ServerLevel server) {
                HoistGroups.get(server).leaveAll(pos);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChainHoistBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Both sides: the server for authority and safety, the client so the chain and
        // the flown load move smoothly between sync packets.
        return type == NdiDisplays.CHAIN_HOIST_BE.get()
                ? (lvl, pos, st, be) ->
                        ChainHoistBlockEntity.tick(lvl, pos, st, (ChainHoistBlockEntity) be)
                : null;
    }
}
