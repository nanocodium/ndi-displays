package dev.nano.ndidisplays.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The DMX side of a fixed screen (LED wall panel, round or curved screen): patch
 * address, dimmer level and the eight source slots the console can cut between.
 *
 * Kept as one state object so every screen block entity embeds the exact same
 * behaviour instead of re-implementing ~100 lines of NBT and clamping each.
 */
public class ScreenDmxState {

    /** Number of NDI source slots the DMX source-select channel indexes into. */
    public static final int SLOTS = 8;
    /** Footprint: CH1 dimmer, CH2 source select (one slot per 32 DMX values). */
    public static final int CHANNEL_COUNT = 2;

    private static final UUID NULL_UUID = new UUID(0, 0);

    private int universe;
    private int address = 1;
    private UUID networkId = NULL_UUID;
    @Nullable
    private byte[] deviceId;
    /** The Theatrical DMXConsumer delegate; opaque so screens load without Theatrical. */
    @Nullable
    private Object consumer;

    /** DMX dimmer 0-255; 255 when unpatched so screens work without a desk. */
    private int dimmer = 255;
    private final String[] slots = new String[SLOTS];

    public ScreenDmxState() {
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = "";
        }
    }

    public int getUniverse() {
        return universe;
    }

    public int getAddress() {
        return address;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    @Nullable
    public byte[] getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(byte[] bytes) {
        this.deviceId = bytes;
    }

    @Nullable
    public Object getConsumer() {
        return consumer;
    }

    public void setConsumer(@Nullable Object consumer) {
        this.consumer = consumer;
    }

    public int getDimmer() {
        return dimmer;
    }

    /** @return true when the value changed. */
    public boolean setDimmer(int value) {
        int clamped = Clamps.i(value, 0, 255);
        if (clamped == dimmer) {
            return false;
        }
        dimmer = clamped;
        return true;
    }

    /** Brightness multiplier the renderer applies on top of the configured level. */
    public float dimmerFactor() {
        return dimmer / 255.0F;
    }

    public String getSlot(int index) {
        return slots[Clamps.i(index, 0, SLOTS - 1)];
    }

    public void setSlot(int index, String source) {
        slots[Clamps.i(index, 0, SLOTS - 1)] = Clamps.name(source, LedPanelBlockEntity.MAX_SOURCE_NAME);
    }

    /** The slot a raw source-select byte lands in: 0-31 = slot 0, ..., 224-255 = slot 7. */
    @Nullable
    public String slotForByte(int sourceByte) {
        String slot = slots[Clamps.i(sourceByte, 0, 255) / 32];
        return slot.isEmpty() ? null : slot;
    }

    /** Theatrical configuration-card patch; null keeps the current value. */
    public void applyPatch(@Nullable UUID network, @Nullable Integer universe, @Nullable Integer address) {
        if (network != null) {
            this.networkId = network;
        }
        if (universe != null) {
            this.universe = Clamps.i(universe, 0, 32767);
        }
        if (address != null) {
            this.address = Clamps.i(address, 1, 512);
        }
    }

    public void save(CompoundTag tag) {
        tag.putInt("DmxUniverse", universe);
        tag.putInt("DmxAddress", address);
        tag.putUUID("DmxNetwork", networkId);
        tag.putInt("DmxDimmer", dimmer);
        if (deviceId != null) {
            tag.putByteArray("DmxDeviceId", deviceId);
        }
        ListTag list = new ListTag();
        for (String slot : slots) {
            list.add(StringTag.valueOf(slot));
        }
        tag.put("DmxSlots", list);
    }

    public void load(CompoundTag tag) {
        universe = Clamps.i(tag.getInt("DmxUniverse"), 0, 32767);
        address = Clamps.i(tag.contains("DmxAddress") ? tag.getInt("DmxAddress") : 1, 1, 512);
        networkId = tag.hasUUID("DmxNetwork") ? tag.getUUID("DmxNetwork") : NULL_UUID;
        dimmer = Clamps.i(tag.contains("DmxDimmer") ? tag.getInt("DmxDimmer") : 255, 0, 255);
        if (tag.contains("DmxDeviceId")) {
            deviceId = tag.getByteArray("DmxDeviceId");
        }
        ListTag list = tag.getList("DmxSlots", Tag.TAG_STRING);
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = i < list.size()
                    ? Clamps.name(list.getString(i), LedPanelBlockEntity.MAX_SOURCE_NAME) : "";
        }
    }
}
