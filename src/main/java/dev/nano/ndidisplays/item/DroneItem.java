package dev.nano.ndidisplays.item;

import dev.nano.ndidisplays.entity.DroneEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Places a parked drone on the clicked face. */
public class DroneItem extends Item {

    public DroneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();
        Vec3 pos = Vec3.atCenterOf(clicked.relative(face)).subtract(0.0, 0.35, 0.0);
        if (face == Direction.UP) {
            pos = Vec3.atBottomCenterOf(clicked.above()).add(0.0, 0.05, 0.0);
        }
        if (!level.isClientSide) {
            DroneEntity drone = DroneEntity.create(level, pos, context.getRotation() - 180.0F);
            level.addFreshEntity(drone);
            level.playSound(null, BlockPos.containing(pos), SoundEvents.ITEM_FRAME_PLACE,
                    SoundSource.PLAYERS, 0.6F, 1.2F);
            if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
