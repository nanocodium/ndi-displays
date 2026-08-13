package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.block.KineticWinchBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * NDI configuration card — the video-world twin of Theatrical's DMX configuration
 * card. Sneak + right-click to open the menu and pick an NDI source on the card;
 * right-click any screen (LED wall or kinetic winch tile) to switch it to NDI
 * video with that source. The application itself lives in the blocks' use() handlers.
 *
 * WorldEdit-style region selection for big motor arrays: sneak + right-click a winch
 * to set corner 1, sneak + left-click another winch to set corner 2 (a wireframe box
 * is drawn between them), then open the menu and apply the source to every screen in
 * the box at once.
 */
public class NdiConfigCardItem extends Item {

    public static final String TAG_SOURCE = "ndiSource";
    public static final String TAG_POS1 = "selPos1";
    public static final String TAG_POS2 = "selPos2";
    /** Dimension the selection lives in; a corner set in another dimension resets it. */
    public static final String TAG_DIM = "selDim";
    /** Winch motor mode the card imposes: 0 leave unchanged, 1 linked, 2 twin. */
    public static final String TAG_WINCH_MODE = "winchMode";

    public static final int WINCH_MODE_KEEP = 0;
    public static final int WINCH_MODE_LINKED = 1;
    public static final int WINCH_MODE_TWIN = 2;

    public NdiConfigCardItem(Properties properties) {
        super(properties);
    }

    public static String storedSource(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(TAG_SOURCE);
    }

    /** The winch motor mode this card imposes (WINCH_MODE_KEEP when none). */
    public static int storedWinchMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        int mode = tag == null ? WINCH_MODE_KEEP : tag.getInt(TAG_WINCH_MODE);
        return mode >= WINCH_MODE_KEEP && mode <= WINCH_MODE_TWIN ? mode : WINCH_MODE_KEEP;
    }

    @Nullable
    public static BlockPos selectionPos(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(key) ? BlockPos.of(tag.getLong(key)) : null;
    }

    /** True when both corners are set (in the same dimension, by construction). */
    public static boolean hasSelection(ItemStack stack) {
        return selectionPos(stack, TAG_POS1) != null && selectionPos(stack, TAG_POS2) != null;
    }

    public static void clearSelection(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(TAG_POS1);
            tag.remove(TAG_POS2);
            tag.remove(TAG_DIM);
        }
    }

    /** Sets one selection corner, dropping the other corner if the dimension changed. */
    public static void setSelectionPos(ItemStack stack, Level level, String key, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTag();
        String dim = level.dimension().location().toString();
        if (!dim.equals(tag.getString(TAG_DIM))) {
            tag.remove(TAG_POS1);
            tag.remove(TAG_POS2);
            tag.putString(TAG_DIM, dim);
        }
        tag.putLong(key, pos.asLong());
    }

    /** The dimension id the selection was made in, or empty when there is none. */
    public static String selectionDimension(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(TAG_DIM);
    }

    /**
     * Sneak + right-click on a winch sets selection corner 1 (WorldEdit's wand, pos1).
     * Sneak + right-click on anything else falls through to {@link #use} and opens the menu.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof KineticWinchBlock)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            setSelectionPos(context.getItemInHand(), level, TAG_POS1, pos);
            player.displayClientMessage(Component.translatable(
                    "item.ndidisplays.ndi_config_card.pos1",
                    pos.getX(), pos.getY(), pos.getZ()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // The menu only opens while sneaking, matching Theatrical's configuration card;
        // a plain right-click is reserved for applying the card to screens.
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.nano.ndidisplays.client.ClientHooks.openNdiCardConfig(hand));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        String source = storedSource(stack);
        if (!source.isEmpty()) {
            tooltip.add(Component.translatable("item.ndidisplays.ndi_config_card.source", source)
                    .withStyle(ChatFormatting.AQUA));
        }
        int mode = storedWinchMode(stack);
        if (mode != WINCH_MODE_KEEP) {
            tooltip.add(Component.translatable(mode == WINCH_MODE_TWIN
                            ? "item.ndidisplays.ndi_config_card.mode_twin"
                            : "item.ndidisplays.ndi_config_card.mode_linked")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        BlockPos pos1 = selectionPos(stack, TAG_POS1);
        BlockPos pos2 = selectionPos(stack, TAG_POS2);
        if (pos1 != null && pos2 != null) {
            tooltip.add(Component.translatable("item.ndidisplays.ndi_config_card.selection",
                            Math.abs(pos2.getX() - pos1.getX()) + 1,
                            Math.abs(pos2.getY() - pos1.getY()) + 1,
                            Math.abs(pos2.getZ() - pos1.getZ()) + 1)
                    .withStyle(ChatFormatting.GREEN));
        } else if (pos1 != null) {
            tooltip.add(Component.translatable("item.ndidisplays.ndi_config_card.selection_partial")
                    .withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("item.ndidisplays.ndi_config_card.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.ndidisplays.ndi_config_card.desc2")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.ndidisplays.ndi_config_card.desc3")
                .withStyle(ChatFormatting.GRAY));
    }
}
