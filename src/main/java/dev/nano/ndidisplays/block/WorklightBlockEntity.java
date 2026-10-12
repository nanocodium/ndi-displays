package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.worklight.WorklightGroups;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A 2 kW halogen work light: the flood a crew switches on to see the stage between shows.
 * Holds the tilt of the lamp head in its yoke and the name of the group a remote switches
 * it with. On / off lives in the block state so the block light level follows it.
 */
public class WorklightBlockEntity extends BlockEntity {

    public static final float MIN_TILT = 0.0F;
    public static final float MAX_TILT = 90.0F;
    public static final float TILT_STEP = 15.0F;
    public static final float DEFAULT_TILT = 30.0F;

    private String group = "";
    private float tilt = DEFAULT_TILT;

    public WorklightBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.WORKLIGHT_BE.get(), pos, state);
    }

    public String getGroup() {
        return group;
    }

    /** Degrees the head is tilted down from horizontal. */
    public float getTilt() {
        return tilt;
    }

    public Direction getFacing() {
        return getBlockState().getValue(WorklightBlock.FACING);
    }

    public boolean isLit() {
        return getBlockState().getValue(WorklightBlock.LIT);
    }

    /** Server side: moves the lamp between groups, keeping the saved index in step. */
    public void setGroup(String newGroup) {
        String normalised = WorklightGroups.normalise(newGroup);
        if (level instanceof ServerLevel server) {
            WorklightGroups groups = WorklightGroups.get(server);
            groups.leaveAll(worldPosition);
            if (!normalised.isEmpty()) {
                groups.join(normalised, worldPosition);
            }
        }
        this.group = normalised;
        setChanged();
        sync();
    }

    public void setTilt(float degrees) {
        this.tilt = Mth.clamp(degrees, MIN_TILT, MAX_TILT);
        setChanged();
        sync();
    }

    /** One notch further down, wrapping back to level. */
    public void stepTilt() {
        float next = tilt + TILT_STEP;
        setTilt(next > MAX_TILT + 0.01F ? MIN_TILT : next);
    }

    /** Switches the lamp; the block light level rides on the LIT state. */
    public void setLit(boolean lit) {
        if (level == null || isLit() == lit) {
            return;
        }
        level.setBlock(worldPosition, getBlockState().setValue(WorklightBlock.LIT, lit), 3);
    }

    private void sync() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /** Drops the index entry when the lamp is mined. */
    public void onRemoved() {
        if (level instanceof ServerLevel server) {
            WorklightGroups.get(server).leaveAll(worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Group", group);
        tag.putFloat("Tilt", tilt);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        group = WorklightGroups.normalise(tag.getString("Group"));
        tilt = tag.contains("Tilt") ? Mth.clamp(tag.getFloat("Tilt"), MIN_TILT, MAX_TILT) : DEFAULT_TILT;
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
}
