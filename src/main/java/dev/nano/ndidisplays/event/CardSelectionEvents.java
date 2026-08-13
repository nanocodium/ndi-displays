package dev.nano.ndidisplays.event;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.KineticWinchBlock;
import dev.nano.ndidisplays.item.NdiConfigCardItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * WorldEdit-style second corner for the NDI configuration card: sneak + LEFT-click a
 * winch sets pos2 (the sneak + right-click pos1 lives in the item's useOn). The event
 * is cancelled so the punch never starts breaking the winch.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CardSelectionEvents {

    private CardSelectionEvents() {
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();
        if (!player.isShiftKeyDown() || !(held.getItem() instanceof NdiConfigCardItem)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!(event.getLevel().getBlockState(pos).getBlock() instanceof KineticWinchBlock)) {
            return;
        }
        event.setCanceled(true);
        if (!event.getLevel().isClientSide) {
            NdiConfigCardItem.setSelectionPos(held, event.getLevel(),
                    NdiConfigCardItem.TAG_POS2, pos);
            player.displayClientMessage(Component.translatable(
                    "item.ndidisplays.ndi_config_card.pos2",
                    pos.getX(), pos.getY(), pos.getZ()), true);
        }
    }
}
