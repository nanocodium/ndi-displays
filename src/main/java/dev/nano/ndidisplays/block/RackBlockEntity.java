package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A 19-inch equipment rack: six 1U slots that hold real units.
 *
 * The rack is the physical layer of a machine room. Units are items — carry a web module across
 * the stage, slot it in, pull it back out — and each seated unit keeps its own configuration in
 * its slot (a web module its URL, the PDU its switch state). Power is the game: a rack runs only
 * while it holds a PDU that is switched on. No PDU, or PDU off, and every screen in the rack goes
 * dark and every LED dies — rackmount gear behaves like rackmount gear.
 */
public class RackBlockEntity extends BlockEntity {

    public static final int SLOTS = 6;
    /** First slot's base height and the per-slot pitch, in block units (matches the frame mesh). */
    public static final float SLOT_Y0 = 0.045F;
    public static final float SLOT_PITCH = 0.1525F;

    /** Slot contents: 0 = empty, otherwise RackUnitType ordinal + 1. */
    private final byte[] slots = new byte[SLOTS];
    private final CompoundTag[] config = new CompoundTag[SLOTS];

    public RackBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.RACK_BE.get(), pos, state);
        for (int i = 0; i < SLOTS; i++) {
            config[i] = new CompoundTag();
        }
    }

    @Nullable
    public RackUnitType unit(int slot) {
        if (slot < 0 || slot >= SLOTS || slots[slot] == 0) {
            return null;
        }
        return RackUnitType.values()[slots[slot] - 1];
    }

    public CompoundTag cfg(int slot) {
        return config[Math.floorMod(slot, SLOTS)];
    }

    public Direction getFacing() {
        return getBlockState().getValue(RackBlock.FACING);
    }

    /** The rack has power: some PDU is seated and switched on. */
    public boolean powered() {
        for (int i = 0; i < SLOTS; i++) {
            if (unit(i) == RackUnitType.PDU && cfg(i).getBoolean("On")) {
                return true;
            }
        }
        return false;
    }

    /** Seats a unit in the preferred slot if free, else the lowest free slot; -1 when full. */
    public int insert(RackUnitType type, int preferred) {
        if (preferred >= 0 && preferred < SLOTS && slots[preferred] == 0) {
            return seat(type, preferred);
        }
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == 0) {
                return seat(type, i);
            }
        }
        return -1;
    }

    private int seat(RackUnitType type, int slot) {
        slots[slot] = (byte) (type.ordinal() + 1);
        config[slot] = new CompoundTag();
        if (type == RackUnitType.PDU) {
            config[slot].putBoolean("On", true); // ships with the breaker closed
        }
        setChanged();
        return slot;
    }

    /** Pulls the unit from a slot; null when it was empty. */
    @Nullable
    public RackUnitType remove(int slot) {
        RackUnitType type = unit(slot);
        if (type != null) {
            slots[slot] = 0;
            config[slot] = new CompoundTag();
            setChanged();
        }
        return type;
    }

    /** The slot a hit at this block-local height lands in. */
    public static int slotAt(double localY) {
        return Math.max(0, Math.min(SLOTS - 1, (int) ((localY - SLOT_Y0) / SLOT_PITCH)));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putByteArray("Slots", slots.clone());
        for (int i = 0; i < SLOTS; i++) {
            tag.put("Cfg" + i, config[i].copy());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        byte[] loaded = tag.getByteArray("Slots");
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = i < loaded.length
                    ? (byte) Clamps.i(loaded[i], 0, RackUnitType.values().length) : 0;
            config[i] = tag.contains("Cfg" + i) ? tag.getCompound("Cfg" + i) : new CompoundTag();
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) {
            load(packet.getTag());
        }
    }

    /** Synthetic browser key for a web module slot: far above the build limit, collision-free. */
    public BlockPos webKey(int slot) {
        return worldPosition.offset(0, 4096 + slot, 0);
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            for (int i = 0; i < SLOTS; i++) {
                dev.nano.ndidisplays.client.web.WebBrowsers.close(webKey(i));
            }
        }
        super.setRemoved();
    }
}
