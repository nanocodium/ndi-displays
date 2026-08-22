package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * One tile of a walkable LED floor. Adjacent same-facing tiles merge into a single
 * video rectangle drawn by the anchor, exactly like {@link LedPanelBlockEntity} walls
 * but in the XZ plane.
 */
public class LedFloorBlockEntity extends BlockEntity implements DmxScreen {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;
    public static final int PATTERN_COUNT = LedPanelBlockEntity.PATTERN_COUNT;

    private static final int DEFAULT_PX_PER_BLOCK = 256;
    private static final float DEFAULT_BRIGHTNESS = 0.85F;
    private static final float DEFAULT_GAMMA = 2.2F;
    private static final int DEFAULT_PATTERN = 1;

    private String sourceName = "";
    private int pixelsPerBlock = DEFAULT_PX_PER_BLOCK;
    private float brightness = DEFAULT_BRIGHTNESS;
    private float gamma = DEFAULT_GAMMA;
    private int testPattern = DEFAULT_PATTERN;

    private FloorScanner.FloorInfo cachedFloor;
    private boolean cachedRenderAnchor;
    private long cacheTime = Long.MIN_VALUE;

    private final ScreenDmxState dmx = new ScreenDmxState();
    private final CropWindow crop = new CropWindow();

    public LedFloorBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.LED_FLOOR_BE.get(), pos, state);
    }

    public CropWindow crop() {
        return crop;
    }

    public Direction getFacing() {
        return getBlockState().getValue(LedFloorBlock.FACING);
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getPixelsPerBlock() {
        return pixelsPerBlock;
    }

    public float getBrightness() {
        return brightness;
    }

    public float getEffectiveBrightness() {
        return brightness * dmx.dimmerFactor();
    }

    public float getGamma() {
        return gamma;
    }

    public int getTestPattern() {
        return testPattern;
    }

    public Block getPanelKind() {
        return getBlockState().getBlock();
    }

    public void applyConfig(String source, int pxPerBlock, float brightness, float gamma, int pattern) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.pixelsPerBlock = Clamps.i(pxPerBlock, 8, 1024);
        this.brightness = Clamps.f(brightness, 0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        this.gamma = Clamps.f(gamma, 1.0F, 3.0F, DEFAULT_GAMMA);
        this.testPattern = Clamps.i(pattern, 0, PATTERN_COUNT - 1);
        setChanged();
    }

    public void applyNdiCard(String source) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.testPattern = 0;
        setChanged();
    }

    public boolean isRenderAnchor() {
        if (level == null) {
            return false;
        }
        getFloorInfo();
        return cachedRenderAnchor;
    }

    public FloorScanner.FloorInfo getFloorInfo() {
        Level lvl = level;
        if (lvl == null) {
            return null;
        }
        long now = lvl.getGameTime();
        if (cachedFloor == null || now - cacheTime > 40 || now < cacheTime) {
            Direction facing = getFacing();
            Block kind = getPanelKind();
            BlockPos anchor = FloorScanner.findAnchor(lvl, worldPosition, facing, kind);
            FloorScanner.FloorInfo rect = FloorScanner.scan(lvl, anchor, facing, kind);
            if (FloorScanner.contains(rect, worldPosition)
                    && FloorScanner.isIsolatedRectangle(lvl, rect, kind)) {
                cachedFloor = rect;
                cachedRenderAnchor = worldPosition.equals(anchor);
            } else {
                cachedFloor = new FloorScanner.FloorInfo(worldPosition, facing, 1, 1);
                cachedRenderAnchor = true;
            }
            cacheTime = now;
        }
        return cachedFloor;
    }

    @Override
    public ScreenDmxState dmx() {
        return dmx;
    }

    @Override
    public String getDmxModelName() {
        return "NDI LED Floor";
    }

    @Override
    public String getDmxTranslationKey() {
        return getBlockState().getBlock().getDescriptionId();
    }

    @Override
    public void applyDmxFrame(int dimmer, int sourceByte) {
        if (level == null || level.isClientSide) {
            return;
        }
        String slot = dmx.slotForByte(sourceByte);
        FloorScanner.FloorInfo floor = getFloorInfo();
        if (floor == null) {
            acceptFloorDmx(dimmer, slot);
            return;
        }
        Direction right = FloorScanner.right(floor.facing());
        for (int w = 0; w < floor.width(); w++) {
            for (int d = 0; d < floor.depth(); d++) {
                BlockPos p = floor.anchor().relative(right, w).relative(floor.facing(), d);
                if (level.getBlockEntity(p) instanceof LedFloorBlockEntity tile) {
                    tile.acceptFloorDmx(dimmer, slot);
                }
            }
        }
    }

    private void acceptFloorDmx(int dimmer, @Nullable String slotSource) {
        boolean changed = dmx.setDimmer(dimmer);
        if (slotSource != null && (!slotSource.equals(sourceName) || testPattern != 0)) {
            sourceName = slotSource;
            testPattern = 0;
            changed = true;
        }
        if (changed && level != null) {
            setChanged();
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && !level.isClientSide) {
            dev.nano.ndidisplays.compat.theatrical.TheatricalCompat.registerScreen(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            dev.nano.ndidisplays.compat.theatrical.TheatricalCompat.unregisterScreen(this);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        // Server chunk unload never calls setRemoved, so without this the DMX consumer — which
        // holds this block entity — stayed registered in Theatrical's network for the whole
        // session, and DMX arriving for the unloaded screen force-loaded the chunk on every
        // frame the desk sent. A reload re-registers through setLevel.
        if (level != null && !level.isClientSide) {
            dev.nano.ndidisplays.compat.theatrical.TheatricalCompat.unregisterScreen(this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Source", sourceName);
        tag.putInt("PxPerBlock", pixelsPerBlock);
        tag.putFloat("Brightness", brightness);
        tag.putFloat("Gamma", gamma);
        tag.putInt("Pattern", testPattern);
        crop.save(tag);
        dmx.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        sourceName = Clamps.name(tag.getString("Source"), MAX_SOURCE_NAME);
        pixelsPerBlock = Clamps.i(tag.contains("PxPerBlock") ? tag.getInt("PxPerBlock") : DEFAULT_PX_PER_BLOCK,
                8, 1024);
        brightness = Clamps.f(tag.contains("Brightness") ? tag.getFloat("Brightness") : DEFAULT_BRIGHTNESS,
                0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        gamma = Clamps.f(tag.contains("Gamma") ? tag.getFloat("Gamma") : DEFAULT_GAMMA,
                1.0F, 3.0F, DEFAULT_GAMMA);
        testPattern = Clamps.i(tag.contains("Pattern") ? tag.getInt("Pattern") : DEFAULT_PATTERN,
                0, PATTERN_COUNT - 1);
        crop.load(tag);
        dmx.load(tag);
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
        if (level != null && isRenderAnchor()) {
            FloorScanner.FloorInfo floor = getFloorInfo();
            if (floor != null) {
                Direction right = FloorScanner.right(floor.facing());
                BlockPos far = floor.anchor()
                        .relative(right, floor.width() - 1)
                        .relative(floor.facing(), floor.depth() - 1);
                return new AABB(floor.anchor()).minmax(new AABB(far)).inflate(1.0);
            }
        }
        return new AABB(worldPosition);
    }
}
