package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.activecam.ActiveCamBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Anchor + drum for an Active Cam rig. Stores the bound controller, if any. */
public class CameraWinchBlockEntity extends BlockEntity {

    @Nullable
    private BlockPos controllerPos;

    public CameraWinchBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.CAMERA_WINCH_BE.get(), pos, state);
    }

    @Nullable
    public BlockPos getControllerPos() {
        return controllerPos;
    }

    public boolean isBound() {
        return controllerPos != null;
    }

    public void bind(BlockPos controller) {
        this.controllerPos = controller.immutable();
        setChanged();
    }

    public void unbind() {
        this.controllerPos = null;
        setChanged();
    }

    public Vec3 drumWorld() {
        return ActiveCamBounds.drumOf(worldPosition);
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && controllerPos != null) {
            if (level.getBlockEntity(controllerPos) instanceof ActiveCamControllerBlockEntity ctrl) {
                ctrl.onWinchRemoved(worldPosition);
            }
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (controllerPos != null) {
            tag.putLong("Controller", controllerPos.asLong());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        controllerPos = tag.contains("Controller") ? BlockPos.of(tag.getLong("Controller")) : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            load(pkt.getTag());
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(80.0);
    }
}
