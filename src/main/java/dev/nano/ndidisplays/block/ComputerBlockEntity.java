package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

import javax.annotation.Nullable;

/**
 * A personal computer: a placeable machine with its own native operating system.
 *
 * The block holds the machine's world-visible identity — name, output resolution, FPS, whether
 * it broadcasts, who owns it and whether it is locked. The desktop itself (windows, apps, the
 * virtual drive) is client-side runtime state, rendered natively into the computer's private
 * framebuffer and published as the NDI source {@code MC Computer <name>} — no browser engine
 * involved anywhere.
 */
public class ComputerBlockEntity extends BlockEntity {

    public static final int MAX_NAME = 64;

    /** Output resolutions, indexed by {@link #resolution}. */
    public static final int[] RES_W = {854, 1280, 1920};
    public static final int[] RES_H = {480, 720, 1080};

    private String name = "";
    private int resolution = 1;
    private int fps = 30;
    private boolean broadcast = true;
    private boolean locked;
    @Nullable
    private UUID owner;

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.COMPUTER_BE.get(), pos, state);
    }

    public String getName() {
        return name;
    }

    public int getResolution() {
        return resolution;
    }

    public int getWidth() {
        return RES_W[Math.floorMod(resolution, RES_W.length)];
    }

    public int getHeight() {
        return RES_H[Math.floorMod(resolution, RES_H.length)];
    }

    public int getFps() {
        return fps;
    }

    public boolean isBroadcasting() {
        return broadcast;
    }

    public boolean isLocked() {
        return locked;
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public boolean mayUse(net.minecraft.world.entity.player.Player player) {
        return !locked || owner == null || player.getUUID().equals(owner)
                || player.hasPermissions(2);
    }

    public Direction getFacing() {
        return getBlockState().getValue(ComputerBlock.FACING);
    }

    /** The NDI source name; position-derived until the operator names the machine. */
    public String getEffectiveSourceName() {
        if (!name.isBlank()) {
            return "MC Computer " + name;
        }
        return "MC Computer " + worldPosition.getX() + "," + worldPosition.getY() + ","
                + worldPosition.getZ();
    }

    public void applyComputerConfig(String name, int resolution, int fps, boolean broadcast,
                                    boolean locked) {
        this.name = Clamps.name(name, MAX_NAME);
        this.resolution = Clamps.i(resolution, 0, RES_W.length - 1);
        this.fps = Clamps.i(fps, 1, 60);
        this.broadcast = broadcast;
        this.locked = locked;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Name", name);
        tag.putInt("Res", resolution);
        tag.putInt("Fps", fps);
        tag.putBoolean("Broadcast", broadcast);
        tag.putBoolean("Locked", locked);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        name = Clamps.name(tag.getString("Name"), MAX_NAME);
        resolution = Clamps.i(tag.getInt("Res"), 0, RES_W.length - 1);
        fps = Clamps.i(tag.contains("Fps") ? tag.getInt("Fps") : 30, 1, 60);
        broadcast = !tag.contains("Broadcast") || tag.getBoolean("Broadcast");
        locked = tag.getBoolean("Locked");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
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

    @Override
    public void setRemoved() {
        // The OS and its framebuffer must not outlive the machine.
        if (level != null && level.isClientSide) {
            dev.nano.ndidisplays.client.computer.Computers.close(worldPosition);
        }
        super.setRemoved();
    }
}
