package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.item.RackUnitItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * The rack enclosure. Interaction is by hand, at the slot you are looking at:
 * a unit item seats itself there (or in the lowest free slot); sneak + empty hand pulls the unit
 * back out as an item; a bare click on a seated PDU flips its breaker, and on a web module opens
 * its terminal. Breaking the rack drops every seated unit.
 */
public class RackBlock extends HorizontalDirectionalBlock implements EntityBlock {

    private static final VoxelShape SHAPE = Block.box(0, 0, 1, 16, 16, 15);

    public RackBlock(Properties properties) {
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
        if (!(level.getBlockEntity(pos) instanceof RackBlockEntity rack)) {
            return InteractionResult.PASS;
        }
        int slot = RackBlockEntity.slotAt(hit.getLocation().y - pos.getY());
        ItemStack held = player.getItemInHand(hand);

        // seat a unit
        if (held.getItem() instanceof RackUnitItem unitItem) {
            if (!level.isClientSide) {
                int seated = rack.insert(unitItem.type, slot);
                if (seated >= 0) {
                    if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                    level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE,
                            SoundSource.BLOCKS, 0.6F, 1.4F);
                    level.sendBlockUpdated(pos, state, state, 3);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // pull a unit
        if (player.isShiftKeyDown() && held.isEmpty()) {
            if (!level.isClientSide) {
                RackUnitType type = rack.remove(slot);
                if (type != null) {
                    Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5,
                            pos.getZ() + 0.5, new ItemStack(
                                    dev.nano.ndidisplays.NdiDisplays.rackUnitItem(type)));
                    level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_OPEN,
                            SoundSource.BLOCKS, 0.6F, 1.2F);
                    level.sendBlockUpdated(pos, state, state, 3);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // operate the seated unit
        RackUnitType type = rack.unit(slot);
        if (type == RackUnitType.PDU) {
            if (!level.isClientSide) {
                boolean on = !rack.cfg(slot).getBoolean("On");
                rack.cfg(slot).putBoolean("On", on);
                rack.setChanged();
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS,
                        0.7F, on ? 1.2F : 0.8F);
                level.sendBlockUpdated(pos, state, state, 3);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (type == RackUnitType.WEB) {
            if (level.isClientSide) {
                final int s = slot;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        dev.nano.ndidisplays.client.ClientHooks.openRackWeb(pos, s));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return type != null
                ? InteractionResult.sidedSuccess(level.isClientSide)
                : InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                         boolean moving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof RackBlockEntity rack) {
            for (int i = 0; i < RackBlockEntity.SLOTS; i++) {
                RackUnitType type = rack.unit(i);
                if (type != null) {
                    Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5,
                            pos.getZ() + 0.5, new ItemStack(
                                    dev.nano.ndidisplays.NdiDisplays.rackUnitItem(type)));
                }
            }
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RackBlockEntity(pos, state);
    }
}
