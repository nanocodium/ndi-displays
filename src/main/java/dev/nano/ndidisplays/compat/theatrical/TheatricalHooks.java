package dev.nano.ndidisplays.compat.theatrical;

import ch.bildspur.artnet.rdm.RDMDeviceId;
import dev.imabad.theatrical.Constants;
import dev.imabad.theatrical.networks.TheatricalNetwork;
import dev.imabad.theatrical.networks.TheatricalNetworkData;
import dev.nano.ndidisplays.block.DmxScreen;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraft.world.level.Level;

import java.util.Random;
import java.util.UUID;

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

    static void registerScreen(DmxScreen screen) {
        TheatricalNetwork network = networkOf(screen.getLevel(), screen.dmx().getNetworkId());
        if (network == null) {
            return;
        }
        if (screen.dmx().getDeviceId() == null) {
            screen.dmx().setDeviceId(newDeviceId(screen.getLevel()));
        }
        network.dmx().addConsumer(screen.getBlockPos(), screenConsumerOf(screen));
    }

    static void unregisterScreen(DmxScreen screen) {
        TheatricalNetwork network = networkOf(screen.getLevel(), screen.dmx().getNetworkId());
        if (network == null) {
            return;
        }
        network.dmx().removeConsumer(screenConsumerOf(screen), screen.getBlockPos());
    }

    private static ScreenDmxConsumer screenConsumerOf(DmxScreen screen) {
        if (screen.dmx().getConsumer() instanceof ScreenDmxConsumer existing) {
            return existing;
        }
        ScreenDmxConsumer consumer = new ScreenDmxConsumer(screen);
        screen.dmx().setConsumer(consumer);
        return consumer;
    }

    private static TheatricalNetwork networkOf(KineticWinchBlockEntity be) {
        return networkOf(be.getLevel(), be.getNetworkId());
    }

    private static TheatricalNetwork networkOf(Level level, UUID networkId) {
        if (level == null || level.isClientSide() || level.getServer() == null) {
            return null;
        }
        return TheatricalNetworkData.getInstance(level.getServer().overworld())
                .getNetwork(networkId);
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
        be.setDmxDeviceId(newDeviceId(be.getLevel()));
    }

    private static byte[] newDeviceId(Level level) {
        byte[] bytes = new byte[4];
        if (level != null) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) level.getRandom().nextInt(256);
            }
        } else {
            new Random().nextBytes(bytes);
        }
        return new RDMDeviceId(Constants.MANUFACTURER_ID, bytes).toBytes();
    }
}
