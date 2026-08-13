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

public class LedPanelBlockEntity extends BlockEntity implements DmxScreen {

    /** Test pattern ids, mirrored in the shader: 0 video, 1 bars, 2 grid, 3 white, 4 red, 5 green, 6 blue, 7 checker. */
    public static final int PATTERN_COUNT = 8;

    /** Matches the wire limit in {@code UpdateWallConfigPacket} and the GUI edit box. */
    public static final int MAX_SOURCE_NAME = 256;

    private static final int DEFAULT_PX_PER_BLOCK = 256; // px per metre; 256 = P3.9 pitch
    private static final float DEFAULT_BRIGHTNESS = 0.85F;
    private static final float DEFAULT_GAMMA = 2.2F;
    private static final int DEFAULT_PATTERN = 1; // fresh panels show colour bars so they visibly work

    private String sourceName = "";
    private int pixelsPerBlock = DEFAULT_PX_PER_BLOCK;
    private float brightness = DEFAULT_BRIGHTNESS;
    private float gamma = DEFAULT_GAMMA;
    private int testPattern = DEFAULT_PATTERN;

    private WallScanner.WallInfo cachedWall;
    private boolean cachedRenderAnchor;
    private long cacheTime = Long.MIN_VALUE;

    private final ScreenDmxState dmx = new ScreenDmxState();
    /** Input window: the region of the source frame this wall displays. */
    private final CropWindow crop = new CropWindow();

    public LedPanelBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.LED_PANEL_BE.get(), pos, state);
    }

    public CropWindow crop() {
        return crop;
    }

    public PanelFacing getFacing() {
        return PanelFacing.of(getBlockState());
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

    /** Panel brightness with the DMX dimmer applied. */
    public float getEffectiveBrightness() {
        return brightness * dmx.dimmerFactor();
    }

    public float getGamma() {
        return gamma;
    }

    public int getTestPattern() {
        return testPattern;
    }

    public void applyConfig(String source, int pxPerBlock, float brightness, float gamma, int pattern) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.pixelsPerBlock = Clamps.i(pxPerBlock, 8, 1024);
        this.brightness = Clamps.f(brightness, 0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        this.gamma = Clamps.f(gamma, 1.0F, 3.0F, DEFAULT_GAMMA);
        this.testPattern = Clamps.i(pattern, 0, PATTERN_COUNT - 1);
        setChanged();
    }

    /** The cabinet kind of this panel — solid and blow-through walls never merge. */
    public Block getPanelKind() {
        return getBlockState().getBlock();
    }

    /** True for see-through mesh/transparent cabinets; drives the render path. */
    public boolean isBlowThrough() {
        return getBlockState().getBlock() instanceof LedPanelBlock panel && panel.isBlowThrough();
    }

    /**
     * True only for the single panel responsible for drawing the wall's quad. Exactly one
     * panel per wall returns true, so a wall is never drawn twice.
     */
    public boolean isRenderAnchor() {
        if (level == null) {
            return false;
        }
        getWallInfo();
        return cachedRenderAnchor;
    }

    /**
     * The wall this panel belongs to, revalidated every 2 seconds (rendering only needs
     * eventual consistency).
     *
     * When the connected panels form a clean isolated rectangle the whole rectangle is one
     * wall and only its bottom-left panel draws it. Otherwise — an L-shape, a wall with a
     * corner knocked out, anything ambiguous — this panel is its own 1x1 screen, because
     * two overlapping rectangles would each draw the shared panels with a different video
     * mapping.
     */
    public WallScanner.WallInfo getWallInfo() {
        Level lvl = level;
        if (lvl == null) {
            return null;
        }
        long now = lvl.getGameTime();
        if (cachedWall == null || now - cacheTime > 40 || now < cacheTime) {
            PanelFacing facing = getFacing();
            Block kind = getPanelKind();
            BlockPos anchor = WallScanner.findAnchor(lvl, worldPosition, facing, kind);
            WallScanner.WallInfo rect = WallScanner.scan(lvl, anchor, facing, kind);
            if (WallScanner.contains(rect, worldPosition)
                    && WallScanner.isIsolatedRectangle(lvl, rect, kind)) {
                cachedWall = rect;
                cachedRenderAnchor = worldPosition.equals(anchor);
            } else {
                cachedWall = new WallScanner.WallInfo(worldPosition, facing, 1, 1);
                cachedRenderAnchor = true;
            }
            cacheTime = now;
        }
        return cachedWall;
    }

    public void invalidateWallCache() {
        cachedWall = null;
    }

    // ------------------------------------------------------------------ DMX (Theatrical)

    @Override
    public ScreenDmxState dmx() {
        return dmx;
    }

    @Override
    public String getDmxModelName() {
        return "NDI LED Wall";
    }

    @Override
    public String getDmxTranslationKey() {
        return getBlockState().getBlock().getDescriptionId();
    }

    /**
     * One DMX frame for the whole wall: the patched panel fans dimmer and source out
     * to every panel of its wall group, so one 2-channel fixture owns the full screen.
     */
    @Override
    public void applyDmxFrame(int dimmer, int sourceByte) {
        if (level == null || level.isClientSide) {
            return;
        }
        String slot = dmx.slotForByte(sourceByte);
        WallScanner.WallInfo wall = getWallInfo();
        if (wall == null) {
            acceptWallDmx(dimmer, slot);
            return;
        }
        net.minecraft.core.Vec3i right = wall.facing().rightStep();
        for (int w = 0; w < wall.width(); w++) {
            for (int h = 0; h < wall.height(); h++) {
                BlockPos p = wall.anchor()
                        .offset(right.getX() * w, right.getY() * w, right.getZ() * w)
                        .above(h);
                if (level.getBlockEntity(p) instanceof LedPanelBlockEntity panel) {
                    panel.acceptWallDmx(dimmer, slot);
                }
            }
        }
    }

    /** Applies DMX dimmer/source to this one panel, syncing only on change. */
    private void acceptWallDmx(int dimmer, @Nullable String slotSource) {
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

    /**
     * Also handles the client sync packet, so every value is re-clamped here rather
     * than trusted: an out-of-range pattern id would crash the config screen when it
     * indexes its label array, and a NaN brightness/gamma would reach a shader uniform.
     */
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
            WallScanner.WallInfo wall = getWallInfo();
            if (wall != null) {
                net.minecraft.core.Vec3i right = wall.facing().rightStep();
                int span = wall.width() - 1;
                BlockPos far = wall.anchor()
                        .offset(right.getX() * span, right.getY() * span, right.getZ() * span)
                        .above(wall.height() - 1);
                AABB box = new AABB(wall.anchor()).minmax(new AABB(far));
                return box.inflate(1.0);
            }
        }
        return new AABB(worldPosition);
    }
}
