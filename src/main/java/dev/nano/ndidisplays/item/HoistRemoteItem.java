package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.hoist.HoistGroups;
import dev.nano.ndidisplays.net.HoistGroupListPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Radio remote for chain hoists — the belly-box a rigger actually runs a rig from.
 *
 * Right-click the air to open the pendant: an emergency stop, a group selector, and
 * up / stop / down. Right-click a motor to patch that motor into the selected group,
 * which is how a group gets built — walk the grid and tap each hoist.
 *
 * The remote holds nothing but the selected group name. Every button is a request to the
 * server, which re-derives the motors, the limits and the safety checks itself; the state
 * shown on the pendant is a snapshot the server pushes back.
 */
public class HoistRemoteItem extends Item {

    public static final String TAG_GROUP = "hoistGroup";
    /** Twist-release mushroom: once down, the remote will not run until it is twisted off. */
    public static final String TAG_ESTOP = "eStop";

    public HoistRemoteItem(Properties properties) {
        super(properties);
    }

    public static String selectedGroup(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : HoistGroups.normalise(tag.getString(TAG_GROUP));
    }

    public static void setGroup(ItemStack stack, String group) {
        stack.getOrCreateTag().putString(TAG_GROUP, HoistGroups.normalise(group));
    }

    public static boolean isEStop(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_ESTOP);
    }

    public static void setEStop(ItemStack stack, boolean down) {
        stack.getOrCreateTag().putBoolean(TAG_ESTOP, down);
    }

    /**
     * Opens the pendant.
     *
     * The screen cannot be opened client-side on its own: the group list lives in the
     * level's saved data, so the server assembles it and the arriving packet is what puts
     * the screen on screen.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            HoistGroupListPacket.open(server, hand, selectedGroup(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        String group = selectedGroup(stack);
        tooltip.add(Component.translatable("item.ndidisplays.hoist_remote.selected",
                        group.isEmpty()
                                ? Component.translatable("gui.ndidisplays.remote.all_groups")
                                : Component.literal(group))
                .withStyle(ChatFormatting.YELLOW));
        if (isEStop(stack)) {
            tooltip.add(Component.translatable("item.ndidisplays.hoist_remote.estop")
                    .withStyle(ChatFormatting.RED));
        }
        tooltip.add(Component.translatable("item.ndidisplays.hoist_remote.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.ndidisplays.hoist_remote.desc2")
                .withStyle(ChatFormatting.GRAY));
    }
}
