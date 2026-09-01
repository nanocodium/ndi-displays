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
 * A production monitor: a desk display that shows one NDI source on its panel — the multiview's
 * single-feed sibling, for director's desks, green rooms and gallery walls. Pure receiver: it
 * holds a source name and a brightness, nothing else.
 */
public class ProMonitorBlockEntity extends BlockEntity {

    public static final int MAX_SOURCE = 128;

    private String sourceName = "";
    private float brightness = 1.0F;

    public ProMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.PRO_MONITOR_BE.get(), pos, state);
    }

    public String getSourceName() {
        return sourceName;
    }

    public float getBrightness() {
        return brightness;
    }

    public Direction getFacing() {
        return getBlockState().getValue(ProMonitorBlock.FACING);
    }

    public void applyConfig(String source, float brightness) {
        this.sourceName = Clamps.name(source, MAX_SOURCE);
        this.brightness = Clamps.f(brightness, 0.1F, 1.0F, 1.0F);
        setChanged();
    }

    /** NDI configuration card: switch the panel to the card's source. */
    public void applyNdiCard(String source) {
        this.sourceName = Clamps.name(source, MAX_SOURCE);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Source", sourceName);
        tag.putFloat("Brightness", brightness);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        sourceName = Clamps.name(tag.getString("Source"), MAX_SOURCE);
        brightness = Clamps.f(tag.contains("Brightness") ? tag.getFloat("Brightness") : 1.0F,
                0.1F, 1.0F, 1.0F);
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
