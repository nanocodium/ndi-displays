package dev.nano.ndidisplays.event;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.CameraKind;
import dev.nano.ndidisplays.block.NdiCameraBlock;
import dev.nano.ndidisplays.entity.JibSeatEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Sneak + right-click a jib to take the operator's seat, leaving a plain right-click to open the
 * camera's configuration like every other rig.
 *
 * Handled here rather than in {@code NdiCameraBlock.use} because of how vanilla dispatches the
 * click: {@code ServerPlayerGameMode.useItemOn} skips block interaction altogether when the
 * player is sneaking <em>and</em> holding anything in either hand, so a sneak binding inside
 * {@code use} silently does nothing unless both hands happen to be empty. Forge's
 * RightClickBlock event fires before that check, which is what makes the binding work whatever
 * the operator is carrying.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class JibRideEvents {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private JibRideEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || player.isPassenger()) {
            return;
        }
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof NdiCameraBlock cam) || cam.getKind() != CameraKind.JIB) {
            return;
        }
        // Cancel on both sides: the click has been consumed by mounting, and letting it fall
        // through would also open the configuration screen or place a block against the jib.
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (level.isClientSide) {
            return;
        }
        // Queued, not mounted here. Sneak is doubly hostile to boarding: vanilla's
        // Entity.canRide is `!isShiftKeyDown() && boardingCooldown <= 0`, so an ordinary mount is
        // refused outright, and holding sneak while riding is also what dismounts you — so
        // forcing the mount just seats the player and throws them straight back off. Waiting for
        // the key to come up sidesteps both instead of fighting them.
        PENDING.put(player.getUUID(), new Pending(event.getPos(), player.tickCount));
    }

    private record Pending(net.minecraft.core.BlockPos jib, int queuedAt) {
    }

    private static final java.util.Map<java.util.UUID, Pending> PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Give up if the key is never released, rather than boarding someone much later. */
    private static final int PENDING_TIMEOUT_TICKS = 60;

    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END
                || event.player.level().isClientSide) {
            return;
        }
        Player player = event.player;
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return;
        }
        if (player.tickCount - pending.queuedAt() > PENDING_TIMEOUT_TICKS || player.isPassenger()) {
            PENDING.remove(player.getUUID());
            return;
        }
        if (player.isShiftKeyDown()) {
            return;
        }
        PENDING.remove(player.getUUID());

        Level level = player.level();
        if (!(level.getBlockState(pending.jib()).getBlock() instanceof NdiCameraBlock cam)
                || cam.getKind() != CameraKind.JIB) {
            return;
        }
        JibSeatEntity seat = JibSeatEntity.create(level, pending.jib());
        // Hand the seat the arm's current pose, so taking control does not jerk the crane.
        if (level.getBlockEntity(pending.jib())
                instanceof dev.nano.ndidisplays.block.NdiCameraBlockEntity jib) {
            float[] arm = jib.getJibArmAngles(1.0F);
            seat.initArm(arm[0], arm[1]);
        }
        level.addFreshEntity(seat);
        if (player.startRiding(seat, true)) {
            player.displayClientMessage(Component.translatable("gui.ndidisplays.jib.seated"), true);
            LOGGER.info("[ndidisplays] {} took the jib seat at {}",
                    player.getGameProfile().getName(), pending.jib());
        } else {
            seat.discard();
            LOGGER.warn("[ndidisplays] jib seat at {} refused the rider", pending.jib());
        }
    }
}
