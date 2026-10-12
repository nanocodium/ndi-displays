package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.block.WorklightBlockEntity;
import dev.nano.ndidisplays.net.NetworkHandler;
import dev.nano.ndidisplays.worklight.WorklightGroups;
import dev.nano.ndidisplays.worklight.WorklightScan;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Radio switch for work lights — the one key a stage hand carries to bring the halogens up
 * between rehearsal and show.
 *
 * The remote's group is its name: rename it in an anvil ("Stage left", "FOH") and tap the
 * lamps to patch them in. An unnamed remote switches every work light in the loaded chunks
 * around you. Right-click the air to toggle: if any lamp of the group is on, all go off;
 * otherwise all come on. Everything is resolved server-side and filtered to the lamps this
 * player may actually operate.
 */
public class WorklightRemoteItem extends Item {

    public WorklightRemoteItem(Properties properties) {
        super(properties);
    }

    /** The group this remote addresses: its anvil name, or empty for "every lamp nearby". */
    public static String group(ItemStack stack) {
        return stack.hasCustomHoverName()
                ? WorklightGroups.normalise(stack.getHoverName().getString())
                : "";
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            toggle(server, stack);
            player.getCooldowns().addCooldown(this, 5);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void toggle(ServerPlayer player, ItemStack stack) {
        ServerLevel level = player.serverLevel();
        List<WorklightBlockEntity> lamps = new ArrayList<>();
        for (WorklightBlockEntity lamp : WorklightScan.resolve(level, group(stack), player.blockPosition())) {
            if (lamp.getLevel() == level && NetworkHandler.mayOperateRemote(player, lamp.getBlockPos())) {
                lamps.add(lamp);
            }
        }
        if (lamps.isEmpty()) {
            player.displayClientMessage(Component.translatable("gui.ndidisplays.worklight.remote_none"), true);
            return;
        }
        boolean anyOn = false;
        for (WorklightBlockEntity lamp : lamps) {
            anyOn |= lamp.isLit();
        }
        boolean target = !anyOn;
        for (WorklightBlockEntity lamp : lamps) {
            lamp.setLit(target);
        }
        player.displayClientMessage(Component.translatable(
                target ? "gui.ndidisplays.worklight.remote_on" : "gui.ndidisplays.worklight.remote_off",
                lamps.size()), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        String group = group(stack);
        tooltip.add(Component.translatable("item.ndidisplays.worklight_remote.group",
                        group.isEmpty()
                                ? Component.translatable("item.ndidisplays.worklight_remote.all")
                                : Component.literal(group))
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("item.ndidisplays.worklight_remote.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.ndidisplays.worklight_remote.desc2")
                .withStyle(ChatFormatting.GRAY));
    }
}
