package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * Multiview monitor — the video engineer's control screen: a wall monitor showing a
 * 2x2 or 3x3 mosaic of NDI sources, each cell a direct emissive video quad (this is
 * a monitor, not an LED wall — no pixel simulation) with the source name captioned
 * under it.
 */
public class MultiviewBlockEntity extends BlockEntity {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;

    public static final int LAYOUT_2X2 = 0;
    public static final int LAYOUT_3X3 = 1;
    public static final int MAX_CELLS = 9;

    public static final float MIN_WIDTH = 1.0F;
    public static final float MAX_WIDTH = 12.0F;

    private static final float DEFAULT_WIDTH = 4.0F;
    private static final float DEFAULT_BRIGHTNESS = 0.9F;

    private int layout = LAYOUT_2X2;
    private float screenWidth = DEFAULT_WIDTH;
    private float brightness = DEFAULT_BRIGHTNESS;
    private final String[] sources = new String[MAX_CELLS];

    public MultiviewBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.MULTIVIEW_BE.get(), pos, state);
        for (int i = 0; i < MAX_CELLS; i++) {
            sources[i] = "";
        }
    }

    public int getLayout() {
        return layout;
    }

    /** Cells per side: 2 for the quad split, 3 for the nine-way. */
    public int gridSize() {
        return layout == LAYOUT_3X3 ? 3 : 2;
    }

    public float getScreenWidth() {
        return screenWidth;
    }

    /** 16:9 monitor. */
    public float getScreenHeight() {
        return screenWidth * 9.0F / 16.0F;
    }

    public float getBrightness() {
        return brightness;
    }

    public String getSource(int cell) {
        return sources[Clamps.i(cell, 0, MAX_CELLS - 1)];
    }

    public Direction getFacing() {
        return getBlockState().getValue(MultiviewBlock.FACING);
    }

    public void applyConfig(int layout, float width, float brightness, String[] newSources) {
        this.layout = Clamps.i(layout, 0, 1);
        this.screenWidth = Clamps.f(width, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH);
        this.brightness = Clamps.f(brightness, 0.05F, 1.0F, DEFAULT_BRIGHTNESS);
        for (int i = 0; i < MAX_CELLS; i++) {
            sources[i] = i < newSources.length
                    ? Clamps.name(newSources[i], MAX_SOURCE_NAME) : "";
        }
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Layout", layout);
        tag.putFloat("ScreenWidth", screenWidth);
        tag.putFloat("Brightness", brightness);
        ListTag list = new ListTag();
        for (String s : sources) {
            list.add(StringTag.valueOf(s));
        }
        tag.put("Sources", list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        layout = Clamps.i(tag.getInt("Layout"), 0, 1);
        screenWidth = Clamps.f(tag.contains("ScreenWidth") ? tag.getFloat("ScreenWidth") : DEFAULT_WIDTH,
                MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH);
        brightness = Clamps.f(tag.contains("Brightness") ? tag.getFloat("Brightness") : DEFAULT_BRIGHTNESS,
                0.05F, 1.0F, DEFAULT_BRIGHTNESS);
        ListTag list = tag.getList("Sources", Tag.TAG_STRING);
        for (int i = 0; i < MAX_CELLS; i++) {
            sources[i] = i < list.size() ? Clamps.name(list.getString(i), MAX_SOURCE_NAME) : "";
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

    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(screenWidth * 0.5 + 1.0);
    }
}
