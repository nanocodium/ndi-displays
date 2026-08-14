package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * Binds a WorldEdit-style AABB of kinetic winches and displays their live layout
 * on a control-room monitor.
 */
public class WinchParkMonitorBlockEntity extends BlockEntity {

    public static final float MIN_WIDTH = 1.0F;
    public static final float MAX_WIDTH = 12.0F;
    private static final float DEFAULT_WIDTH = 4.0F;

    private float screenWidth = DEFAULT_WIDTH;
    @Nullable
    private BlockPos parkPos1;
    @Nullable
    private BlockPos parkPos2;
    private String parkDim = "";

    public WinchParkMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.WINCH_PARK_MONITOR_BE.get(), pos, state);
    }

    public float getScreenWidth() {
        return screenWidth;
    }

    public float getScreenHeight() {
        return screenWidth * 9.0F / 16.0F;
    }

    public Direction getFacing() {
        return getBlockState().getValue(WinchParkMonitorBlock.FACING);
    }

    @Nullable
    public BlockPos getParkPos1() {
        return parkPos1;
    }

    @Nullable
    public BlockPos getParkPos2() {
        return parkPos2;
    }

    public String getParkDim() {
        return parkDim;
    }

    public boolean isBound() {
        return parkPos1 != null && parkPos2 != null && !parkDim.isEmpty();
    }

    public boolean isBoundIn(Level level) {
        return isBound() && level.dimension().location().toString().equals(parkDim);
    }

    public void bind(BlockPos pos1, BlockPos pos2, String dim) {
        this.parkPos1 = pos1.immutable();
        this.parkPos2 = pos2.immutable();
        this.parkDim = dim == null ? "" : dim;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat("ScreenWidth", screenWidth);
        if (parkPos1 != null) {
            tag.putLong("ParkPos1", parkPos1.asLong());
        }
        if (parkPos2 != null) {
            tag.putLong("ParkPos2", parkPos2.asLong());
        }
        if (!parkDim.isEmpty()) {
            tag.putString("ParkDim", parkDim);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        screenWidth = Clamps.f(tag.contains("ScreenWidth") ? tag.getFloat("ScreenWidth") : DEFAULT_WIDTH,
                MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH);
        parkPos1 = tag.contains("ParkPos1") ? BlockPos.of(tag.getLong("ParkPos1")) : null;
        parkPos2 = tag.contains("ParkPos2") ? BlockPos.of(tag.getLong("ParkPos2")) : null;
        parkDim = tag.contains("ParkDim") ? tag.getString("ParkDim") : "";
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
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(screenWidth * 0.5 + 1.0);
    }
}
