package dev.nano.ndidisplays.compat.theatrical;

import ch.bildspur.artnet.rdm.RDMDeviceId;
import dev.imabad.theatrical.api.dmx.DMXConsumer;
import dev.nano.ndidisplays.block.DmxScreen;
import dev.nano.ndidisplays.block.ScreenDmxState;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Theatrical DMX consumer delegate for a fixed screen (LED wall, round or curved
 * screen). 2-channel footprint:
 *
 * <pre>
 *   1  Dimmer          scales the screen's video brightness, 0 = blackout
 *   2  Source select   eight NDI slots, one per 32 DMX values (0-31 = slot 1, ...)
 * </pre>
 *
 * Patch a whole video wall as one 2-channel fixture and the desk owns it: fade the
 * wall out with the grand master, cut between preconfigured feeds from a cue stack.
 */
final class ScreenDmxConsumer implements DMXConsumer {

    private final DmxScreen screen;

    ScreenDmxConsumer(DmxScreen screen) {
        this.screen = screen;
    }

    @Override
    public int getChannelCount() {
        return ScreenDmxState.CHANNEL_COUNT;
    }

    @Override
    public int getChannelStart() {
        return screen.dmx().getAddress();
    }

    @Override
    public int getUniverse() {
        return screen.dmx().getUniverse();
    }

    @Override
    public void consume(byte[] dmxValues) {
        int start = getChannelStart() > 0 ? getChannelStart() - 1 : 0;
        if (dmxValues.length < start + getChannelCount()) {
            return;
        }
        int dimmer = Byte.toUnsignedInt(dmxValues[start]);
        int sourceByte = Byte.toUnsignedInt(dmxValues[start + 1]);
        screen.applyDmxFrame(dimmer, sourceByte);
    }

    @Override
    public RDMDeviceId getDeviceId() {
        byte[] bytes = screen.dmx().getDeviceId();
        return bytes != null ? new RDMDeviceId(bytes) : null;
    }

    @Override
    public int getDeviceTypeId() {
        return 0x08;
    }

    @Override
    public String getModelName() {
        return screen.getDmxModelName();
    }

    @Override
    public ResourceLocation getFixtureId() {
        // Not a registered Theatrical fixture; reuse an id that always exists so RDM
        // screens never look up a missing entry (same trick as the winch consumer).
        return new ResourceLocation("theatrical", "redstone_interface");
    }

    @Override
    public int getActivePersonality() {
        return 0;
    }

    @Override
    public UUID getNetworkId() {
        return screen.dmx().getNetworkId();
    }

    @Override
    public void setNetworkId(UUID newNetworkId) {
        // Network changes go through the configuration card, which re-registers the
        // consumer; Theatrical never drives this directly.
    }

    @Override
    public String getTranslationKey() {
        return screen.getDmxTranslationKey();
    }
}
