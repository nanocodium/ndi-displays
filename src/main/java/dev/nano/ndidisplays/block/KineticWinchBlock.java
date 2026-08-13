package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * Ceiling-mounted kinetic winch. Hangs under the rig/structure and flies an LED video
 * tile below itself on two rendered cables; the tile's height is driven by the GUI or,
 * with Theatrical installed, by DMX (16-bit height + speed + dimmer).
 */
public class KineticWinchBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /** Housing hangs from the ceiling: the top half of the block plus a cable drum. */
    private static final VoxelShape SHAPE = Block.box(2, 6, 2, 14, 16, 14);

    public KineticWinchBlock(Properties properties) {
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

    /**
     * Theatrical's configuration card, matched by registry name so this class never
     * touches a Theatrical type (the mod is a soft dependency).
     */
    private static final ResourceLocation THEATRICAL_CONFIG_CARD =
            new ResourceLocation("theatrical", "configuration_card");

    private static boolean isTheatricalCard(ItemStack stack) {
        return THEATRICAL_CONFIG_CARD.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);

        // NDI configuration card: switch the flying tile to the card's video source.
        if (held.getItem() instanceof dev.nano.ndidisplays.item.NdiConfigCardItem) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof KineticWinchBlockEntity winch) {
                String source = dev.nano.ndidisplays.item.NdiConfigCardItem.storedSource(held);
                winch.applyNdiCard(source);
                level.sendBlockUpdated(pos, state, state, 3);
                player.displayClientMessage(Component.translatable(
                        "item.ndidisplays.ndi_config_card.applied", source), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // A Theatrical/Extra Lights fixture block: hang it from the hook. The winch
        // stores the registry id and its renderer draws the fixture's baked models at
        // the flown height (10-channel DMX footprint: height, speed, head).
        if (held.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
                && dev.nano.ndidisplays.compat.theatrical.TheatricalCompat.isFixtureBlock(blockItem.getBlock())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof KineticWinchBlockEntity winch) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
                // Re-register: the payload change moves the footprint from 4/6 to 10 ch.
                TheatricalCompat.unregister(winch);
                winch.setFixturePayload(id != null ? id.toString() : "");
                TheatricalCompat.register(winch);
                level.sendBlockUpdated(pos, state, state, 3);
                player.displayClientMessage(Component.translatable(
                        "gui.ndidisplays.winch.fixture_loaded", held.getHoverName()), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Theatrical configuration card: patch the winch's DMX exactly like a fixture —
        // network, universe, address, with the card's auto-increment stepping 4 channels.
        if (isTheatricalCard(held)) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof KineticWinchBlockEntity winch) {
                applyTheatricalCard(level, pos, state, player, held, winch);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.nano.ndidisplays.client.ClientHooks.openWinchConfig(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Reads the same NBT keys Theatrical's own fixtures read from the card
     * (network / universeEnabled / dmxUniverse / addressEnabled / dmxAddress /
     * autoIncrement), so one card patches a whole row of lights and winches in order.
     */
    private static void applyTheatricalCard(Level level, BlockPos pos, BlockState state,
                                            Player player, ItemStack card,
                                            KineticWinchBlockEntity winch) {
        CompoundTag tag = card.getOrCreateTag();
        // Unregister from the old network before the id changes, or the consumer leaks.
        TheatricalCompat.unregister(winch);
        winch.applyDmxPatch(
                tag.hasUUID("network") ? tag.getUUID("network") : null,
                tag.getBoolean("universeEnabled") ? tag.getInt("dmxUniverse") : null,
                tag.getBoolean("addressEnabled") ? tag.getInt("dmxAddress") : null);
        TheatricalCompat.register(winch);
        if (tag.getBoolean("autoIncrement")) {
            // Step by this winch's actual footprint: 4 channels LINKED, 6 in TWIN.
            tag.putInt("dmxAddress", tag.getInt("dmxAddress") + winch.getDmxChannelCount());
        }
        level.sendBlockUpdated(pos, state, state, 3);
        player.displayClientMessage(Component.translatable(
                "gui.ndidisplays.winch.dmx_patched",
                winch.getDmxUniverse(), winch.getDmxAddress()), true);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KineticWinchBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Both sides tick: the server owns the position, the client mirrors the same
        // motion profile for smooth rendering between sync packets.
        return type == dev.nano.ndidisplays.NdiDisplays.KINETIC_WINCH_BE.get()
                ? (lvl, pos, st, be) -> KineticWinchBlockEntity.tick(lvl, pos, st, (KineticWinchBlockEntity) be)
                : null;
    }
}
