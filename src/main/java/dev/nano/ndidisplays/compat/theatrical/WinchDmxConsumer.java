package dev.nano.ndidisplays.compat.theatrical;

import ch.bildspur.artnet.rdm.RDMDeviceId;
import dev.imabad.theatrical.api.dmx.DMXConsumer;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Theatrical DMX consumer delegate for a kinetic winch. 4-channel footprint:
 *
 * <pre>
 *   1  Height coarse ┐ 16-bit position, exactly like a moving head's pan/tilt:
 *   2  Height fine   ┘ 0 = tile all the way up, 65535 = all the way down
 *   3  Speed           0 = configured working speed, else scales up to the winch max
 *   4  Dimmer          video brightness, 0 = blackout
 * </pre>
 *
 * With height patched 16-bit, a desk can run {@code Fixture 201 Thru 210 At 100}
 * to drop a whole row, or phase a sine over the height attribute for the Freedom
 * Stage ceiling-wave look. The winch's own motion profile smooths every move.
 */
final class WinchDmxConsumer implements DMXConsumer {

    private final KineticWinchBlockEntity be;

    WinchDmxConsumer(KineticWinchBlockEntity be) {
        this.be = be;
    }

    @Override
    public int getChannelCount() {
        return KineticWinchBlockEntity.DMX_CHANNEL_COUNT;
    }

    @Override
    public int getChannelStart() {
        return be.getDmxAddress();
    }

    @Override
    public int getUniverse() {
        return be.getDmxUniverse();
    }

    @Override
    public void consume(byte[] dmxValues) {
        int start = getChannelStart() > 0 ? getChannelStart() - 1 : 0;
        if (dmxValues.length < start + getChannelCount()) {
            return;
        }
        int coarse = Byte.toUnsignedInt(dmxValues[start]);
        int fine = Byte.toUnsignedInt(dmxValues[start + 1]);
        int speed = Byte.toUnsignedInt(dmxValues[start + 2]);
        int dimmer = Byte.toUnsignedInt(dmxValues[start + 3]);
        be.applyDmx((coarse << 8) | fine, speed, dimmer);
    }

    @Override
    public RDMDeviceId getDeviceId() {
        byte[] bytes = be.getDmxDeviceId();
        return bytes != null ? new RDMDeviceId(bytes) : null;
    }

    @Override
    public int getDeviceTypeId() {
        return 0x05;
    }

    @Override
    public String getModelName() {
        return "Kinetic LED Winch";
    }

    @Override
    public ResourceLocation getFixtureId() {
        // Not a registered Theatrical fixture (this mod has no beam model to offer);
        // reuse an id that always exists so RDM screens never look up a missing entry.
        return new ResourceLocation("theatrical", "redstone_interface");
    }

    @Override
    public int getActivePersonality() {
        return 0;
    }

    @Override
    public UUID getNetworkId() {
        return be.getNetworkId();
    }

    @Override
    public void setNetworkId(UUID newNetworkId) {
        // Network changes go through the winch config packet, which re-registers the
        // consumer; Theatrical never drives this directly.
    }

    @Override
    public String getTranslationKey() {
        return "block.ndidisplays.kinetic_winch";
    }
}
