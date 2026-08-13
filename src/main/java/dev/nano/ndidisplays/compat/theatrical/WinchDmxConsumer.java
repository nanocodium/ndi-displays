package dev.nano.ndidisplays.compat.theatrical;

import ch.bildspur.artnet.rdm.RDMDeviceId;
import dev.imabad.theatrical.api.dmx.DMXConsumer;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Theatrical DMX consumer delegate for a kinetic winch.
 *
 * LINKED mode (default), 4-channel footprint:
 *
 * <pre>
 *   1  Height coarse ┐ 16-bit position, exactly like a moving head's pan/tilt:
 *   2  Height fine   ┘ 0 = tile all the way up, 65535 = all the way down
 *   3  Speed           0 = configured working speed, else scales up to the winch max
 *   4  Dimmer          video brightness, 0 = blackout
 * </pre>
 *
 * TWIN mode, 6-channel footprint — the two cables become independent motors and the
 * tile tilts to follow their height difference:
 *
 * <pre>
 *   1  Winch A coarse ┐ 16-bit
 *   2  Winch A fine   ┘
 *   3  Winch B coarse ┐ 16-bit, clamped within the configured tilt limit around A
 *   4  Winch B fine   ┘
 *   5  Speed
 *   6  Dimmer
 * </pre>
 *
 * Flown fixture, selectable mode (Fixture Config in the winch GUI). The modes are nested,
 * so channel N means the same thing in all three and changing mode never re-shuffles what a
 * desk is already programmed against — it only adds or removes control at the tail:
 *
 * <pre>
 *   1  Height coarse ┐ 16-bit                                  BASIC  COLOUR  FULL
 *   2  Height fine   ┘                                           4ch    7ch   10ch
 *   3  Speed                                                      •      •      •
 *   4  Intensity        the head's dimmer                         •      •      •
 *   5  Red                                                               •      •
 *   6  Green                                                             •      •
 *   7  Blue                                                              •      •
 *   8  Focus                                                                    •
 *   9  Pan              centre-scaled: 128 = centre, ±270°                      •
 *  10  Tilt             centre-scaled: 128 = centre, ±135°                      •
 * </pre>
 *
 * Omitted channels hold rather than zero, as on a real head patched in a smaller mode.
 *
 * With height patched 16-bit, a desk can run {@code Fixture 201 Thru 210 At 100}
 * to drop a whole row, phase a sine over height for the Freedom Stage ceiling wave,
 * or in twin mode phase A against B for tilt waves. The winch's own motion profile
 * smooths every move.
 */
final class WinchDmxConsumer implements DMXConsumer {

    private final KineticWinchBlockEntity be;

    WinchDmxConsumer(KineticWinchBlockEntity be) {
        this.be = be;
    }

    @Override
    public int getChannelCount() {
        return be.getDmxChannelCount();
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
        int payload = be.getPayload();
        if (payload == KineticWinchBlockEntity.PAYLOAD_KINETIC_SPHERE) {
            // Height c/f, speed, dimmer, R, G, B.
            int height = (Byte.toUnsignedInt(dmxValues[start]) << 8)
                    | Byte.toUnsignedInt(dmxValues[start + 1]);
            be.applyDmxSphere(height,
                    Byte.toUnsignedInt(dmxValues[start + 2]),
                    Byte.toUnsignedInt(dmxValues[start + 3]),
                    Byte.toUnsignedInt(dmxValues[start + 4]),
                    Byte.toUnsignedInt(dmxValues[start + 5]),
                    Byte.toUnsignedInt(dmxValues[start + 6]));
            return;
        }
        if (payload == KineticWinchBlockEntity.PAYLOAD_FIXTURE) {
            // Height c/f, speed, intensity, then colour, then focus/pan/tilt — read only as
            // far as the patched mode reaches. The modes are nested, so each is a prefix of
            // the next and channel numbers never shift between them. Channels the mode omits
            // hold their previous values, which the block entity fills in.
            int height = (Byte.toUnsignedInt(dmxValues[start]) << 8)
                    | Byte.toUnsignedInt(dmxValues[start + 1]);
            int speedByte = Byte.toUnsignedInt(dmxValues[start + 2]);

            // Preferred path: the fixture declared its own modes, so read each channel by
            // what its slot says it controls rather than by a fixed position. A mode that
            // omits a control yields -1 and the block entity holds the previous value.
            FixturePersonality p = be.activePersonality();
            if (p != null) {
                int base = start + KineticWinchBlockEntity.WINCH_LEAD_CHANNELS;
                be.applyDmxFixtureMapped(height, speedByte,
                        slot(dmxValues, base, p, FixturePersonality.SLOT_INTENSITY),
                        slot(dmxValues, base, p, FixturePersonality.SLOT_RED),
                        slot(dmxValues, base, p, FixturePersonality.SLOT_GREEN),
                        slot(dmxValues, base, p, FixturePersonality.SLOT_BLUE),
                        slot(dmxValues, base, p, FixturePersonality.SLOT_FOCUS),
                        slot(dmxValues, base, p, FixturePersonality.SLOT_PAN),
                        slot(dmxValues, base, p, FixturePersonality.SLOT_TILT));
                return;
            }

            int intensity = Byte.toUnsignedInt(dmxValues[start + 3]);
            int mode = be.getFixtureMode();
            if (mode == KineticWinchBlockEntity.FIXTURE_MODE_BASIC) {
                be.applyDmxFixture(height, speedByte, intensity);
                return;
            }
            int r = Byte.toUnsignedInt(dmxValues[start + 4]);
            int g = Byte.toUnsignedInt(dmxValues[start + 5]);
            int b = Byte.toUnsignedInt(dmxValues[start + 6]);
            if (mode == KineticWinchBlockEntity.FIXTURE_MODE_COLOUR) {
                be.applyDmxFixture(height, speedByte, intensity, r, g, b);
                return;
            }
            be.applyDmxFixture(height, speedByte, intensity, r, g, b,
                    Byte.toUnsignedInt(dmxValues[start + 7]),
                    Byte.toUnsignedInt(dmxValues[start + 8]),
                    Byte.toUnsignedInt(dmxValues[start + 9]));
            return;
        }
        if (payload == KineticWinchBlockEntity.PAYLOAD_LED_TILE && be.isTwinMode()) {
            int heightA = (Byte.toUnsignedInt(dmxValues[start]) << 8)
                    | Byte.toUnsignedInt(dmxValues[start + 1]);
            int heightB = (Byte.toUnsignedInt(dmxValues[start + 2]) << 8)
                    | Byte.toUnsignedInt(dmxValues[start + 3]);
            int speed = Byte.toUnsignedInt(dmxValues[start + 4]);
            int dimmer = Byte.toUnsignedInt(dmxValues[start + 5]);
            be.applyDmxTwin(heightA, heightB, speed, dimmer);
            return;
        }
        int coarse = Byte.toUnsignedInt(dmxValues[start]);
        int fine = Byte.toUnsignedInt(dmxValues[start + 1]);
        int speed = Byte.toUnsignedInt(dmxValues[start + 2]);
        int dimmer = Byte.toUnsignedInt(dmxValues[start + 3]);
        be.applyDmx((coarse << 8) | fine, speed, dimmer);
    }

    /**
     * The byte carrying {@code slotKind} in this personality, or -1 when the mode has no
     * channel for it (or the frame is too short to contain it).
     */
    private static int slot(byte[] dmxValues, int base, FixturePersonality p, int slotKind) {
        int idx = p.indexOf(slotKind);
        if (idx < 0 || base + idx >= dmxValues.length) {
            return -1;
        }
        return Byte.toUnsignedInt(dmxValues[base + idx]);
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
