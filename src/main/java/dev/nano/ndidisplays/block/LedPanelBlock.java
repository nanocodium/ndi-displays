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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

public class LedPanelBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /**
     * Turns the cabinet 45° counter-clockwise of {@link #FACING}, for walls running along a
     * diagonal staircase of blocks. Kept as a flag beside the existing four-way FACING rather
     * than replacing it with an eight-value property, so walls built before this existed keep
     * their exact blockstates.
     */
    public static final BooleanProperty DIAGONAL = BooleanProperty.create("diagonal");

    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SHAPE_WEST = Block.box(14, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST = Block.box(0, 0, 0, 2, 16, 16);

    /**
     * Diagonal cabinets are a staircase of small boxes approximating the 45° slab, since a
     * VoxelShape cannot itself be rotated. Two shapes cover all four diagonals: opposite
     * facings (north-west and south-east) occupy the same plane and differ only in which way
     * they look.
     */
    private static final VoxelShape SHAPE_DIAGONAL_ANTI = diagonalStaircase(true);
    private static final VoxelShape SHAPE_DIAGONAL_MAIN = diagonalStaircase(false);

    private static VoxelShape diagonalStaircase(boolean anti) {
        VoxelShape shape = Shapes.empty();
        for (int i = 0; i < 8; i++) {
            double x0 = i * 2;
            // anti-diagonal runs corner (0,16) to (16,0); the main diagonal (0,0) to (16,16).
            double z0 = anti ? 14 - i * 2 : i * 2;
            shape = Shapes.or(shape, Block.box(x0, 0, z0, x0 + 2, 16, z0 + 2));
        }
        return shape;
    }

    /**
     * True for see-through "blow-through" cabinets — transparent/mesh LED, the kind hung in
     * front of lighting so the fixtures behind still punch through. Same processor, same
     * wall merging, but a sparse emitter grid rendered with the gaps genuinely open.
     */
    private final boolean blowThrough;

    public LedPanelBlock(Properties properties) {
        this(properties, false);
    }

    public LedPanelBlock(Properties properties, boolean blowThrough) {
        super(properties);
        this.blowThrough = blowThrough;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(DIAGONAL, false));
    }

    public boolean isBlowThrough() {
        return blowThrough;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, DIAGONAL);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        if (face.getAxis().isHorizontal()) {
            // Clicking the side of a block still snaps square to that face, which is what you
            // want when tiling against an existing surface.
            return defaultBlockState().setValue(FACING, face).setValue(DIAGONAL, false);
        }
        // Placing on floor or ceiling takes the player's own heading quantised to 45°, so
        // standing square to the angle you want is how an angled wing gets built. Facing
        // roughly north/east/south/west still gives an axis-aligned panel exactly as before.
        PanelFacing panel = PanelFacing.facingPlayer(ctx.getRotation());
        return defaultBlockState()
                .setValue(FACING, panel.cardinal())
                .setValue(DIAGONAL, panel.isDiagonal());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (state.getValue(DIAGONAL)) {
            // north-west and south-east share the anti-diagonal; north-east and south-west
            // share the main diagonal.
            return switch (state.getValue(FACING)) {
                case NORTH, SOUTH -> SHAPE_DIAGONAL_ANTI;
                default -> SHAPE_DIAGONAL_MAIN;
            };
        }
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // NDI configuration card: swipe the whole wall to NDI video with the card's source.
        if (player.getItemInHand(hand).getItem() instanceof dev.nano.ndidisplays.item.NdiConfigCardItem) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof LedPanelBlockEntity clicked) {
                String source = dev.nano.ndidisplays.item.NdiConfigCardItem
                        .storedSource(player.getItemInHand(hand));
                for (BlockPos panelPos : WallScanner.collectGroup(level, pos, clicked.getFacing(),
                        clicked.getPanelKind())) {
                    if (level.getBlockEntity(panelPos) instanceof LedPanelBlockEntity panel) {
                        panel.applyConfig(source, panel.getPixelsPerBlock(), panel.getBrightness(),
                                panel.getGamma(), 0);
                        BlockState panelState = level.getBlockState(panelPos);
                        level.sendBlockUpdated(panelPos, panelState, panelState, 3);
                    }
                }
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.ndidisplays.ndi_config_card.applied", source), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.nano.ndidisplays.client.ClientHooks.openPanelConfig(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LedPanelBlockEntity(pos, state);
    }
}
