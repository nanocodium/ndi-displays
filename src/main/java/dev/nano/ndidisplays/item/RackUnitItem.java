package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.block.RackUnitType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** A rack unit as an item: right-click an equipment rack to seat it in the slot you aim at. */
public class RackUnitItem extends Item {

    public final RackUnitType type;

    public RackUnitItem(RackUnitType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.ndidisplays.rack_unit.desc"));
        if (type == RackUnitType.PDU) {
            tooltip.add(Component.translatable("item.ndidisplays.rack_unit.pdu"));
        }
    }
}
