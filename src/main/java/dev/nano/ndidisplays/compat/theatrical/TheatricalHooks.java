package dev.nano.ndidisplays.compat.theatrical;

import ch.bildspur.artnet.rdm.RDMDeviceId;
import dev.imabad.theatrical.Constants;
import dev.imabad.theatrical.networks.TheatricalNetwork;
import dev.imabad.theatrical.networks.TheatricalNetworkData;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraft.world.level.Level;

import java.util.Random;

/**
 * The server-side half of the Theatrical integration. Only classloaded when
 * Theatrical is present (see {@link TheatricalCompat}).
 *
 * Registration follows Theatrical's own pattern for non-light consumers
 * (RedstoneInterfaceBlockEntity): look the network up from the overworld's saved
 * data and add/remove the consumer keyed by block position. The consumer object is
 * a delegate rather than the block entity itself, so the block entity class never
 * implements a Theatrical interface and stays loadable without the mod.
 */
final class TheatricalHooks {

    private TheatricalHooks() {
    }

    static void register(KineticWinchBlockEntity be) {
        TheatricalNetwork network = networkOf(be);
        if (network == null) {
            return;
        }
        ensureDeviceId(be);
        network.dmx().addConsumer(be.getBlockPos(), consumerOf(be));
    }

    static void unregister(KineticWinchBlockEntity be) {
        TheatricalNetwork network = networkOf(be);
        if (network == null) {
            return;
        }
        network.dmx().removeConsumer(consumerOf(be), be.getBlockPos());
    }

    private static TheatricalNetwork networkOf(KineticWinchBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide() || level.getServer() == null) {
            return null;
        }
        return TheatricalNetworkData.getInstance(level.getServer().overworld())
                .getNetwork(be.getNetworkId());
    }

    private static WinchDmxConsumer consumerOf(KineticWinchBlockEntity be) {
        if (be.getDmxConsumer() instanceof WinchDmxConsumer existing) {
            return existing;
        }
        WinchDmxConsumer consumer = new WinchDmxConsumer(be);
        be.setDmxConsumer(consumer);
        return consumer;
    }

    private static void ensureDeviceId(KineticWinchBlockEntity be) {
        if (be.getDmxDeviceId() != null) {
            return;
        }
        byte[] bytes = new byte[4];
        Level level = be.getLevel();
        if (level != null) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) level.getRandom().nextInt(256);
            }
        } else {
            new Random().nextBytes(bytes);
        }
        be.setDmxDeviceId(new RDMDeviceId(Constants.MANUFACTURER_ID, bytes).toBytes());
    }
}
