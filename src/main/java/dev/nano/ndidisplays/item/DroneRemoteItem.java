package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.entity.DroneEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * One remote, one drone. Right-click the drone to link; right-click air to enter or leave
 * FPV. Sneak + right-click air opens the NDI / path GUI.
 */
public class DroneRemoteItem extends Item {

    public static final String TAG_DRONE = "droneId";

    public DroneRemoteItem(Properties properties) {
        super(properties);
    }

    public static void link(ItemStack stack, DroneEntity drone) {
        stack.getOrCreateTag().putUUID(TAG_DRONE, drone.getUUID());
    }

    @Nullable
    public static UUID linkedId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(TAG_DRONE) ? tag.getUUID(TAG_DRONE) : null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        UUID id = linkedId(stack);
        if (id == null) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("gui.ndidisplays.drone.unlinked"), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> dev.nano.ndidisplays.client.ClientHooks.openDroneConfig(id));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (!level.isClientSide) {
            DroneEntity drone = DroneEntity.find(level, id);
            if (drone == null) {
                player.displayClientMessage(Component.translatable("gui.ndidisplays.drone.no_signal"), true);
                return InteractionResultHolder.fail(stack);
            }
            if (player.getVehicle() == drone) {
                drone.exitPilot(player);
            } else {
                drone.enterPilot(player);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        UUID id = linkedId(stack);
        if (id == null) {
            tooltip.add(Component.translatable("item.ndidisplays.drone_remote.unlinked")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("item.ndidisplays.drone_remote.linked",
                    id.toString().substring(0, 8)).withStyle(ChatFormatting.AQUA));
        }
    }
}
