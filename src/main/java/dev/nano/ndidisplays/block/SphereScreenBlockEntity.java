package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * A spherical LED screen: one mount block whose renderer draws a video globe of
 * configurable diameter, centred on the block. The source frame is wrapped around it as an
 * equirectangular map (longitude across the frame, latitude down it), with the frame's centre
 * facing the block's FACING direction — feed it a 2:1 panorama and it reads correctly from
 * every side; anything else simply stretches around the globe.
 *
 * Same LED-simulation shaders as the rectangular walls: pitch, brightness, gamma and test
 * patterns behave identically.
 */
public class SphereScreenBlockEntity extends BlockEntity implements DmxScreen {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;
    public static final int PATTERN_COUNT = LedPanelBlockEntity.PATTERN_COUNT;

    /** Globe diameter limits, metres. */
    public static final float MAX_DIAMETER = 512.0F;
    public static final float MIN_DIAMETER = 0.5F;

    private static final int DEFAULT_PX_PER_BLOCK = 128;
    private static final float DEFAULT_BRIGHTNESS = 0.85F;
    private static final float DEFAULT_GAMMA = 2.2F;
    private static final int DEFAULT_PATTERN = 1;
    private static final float DEFAULT_DIAMETER = 3.0F;

    private String sourceName = "";
    private int pixelsPerBlock = DEFAULT_PX_PER_BLOCK;
    private float brightness = DEFAULT_BRIGHTNESS;
    private float gamma = DEFAULT_GAMMA;
    private int testPattern = DEFAULT_PATTERN;
    private float diameter = DEFAULT_DIAMETER;

    private final ScreenDmxState dmx = new ScreenDmxState();
    /** Input window: the region of the source frame wrapped around the globe. */
    private final CropWindow crop = new CropWindow();

    public SphereScreenBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.SPHERE_SCREEN_BE.get(), pos, state);
    }

    public CropWindow crop() {
        return crop;
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

    /** Brightness with the DMX dimmer applied. */
    public float getEffectiveBrightness() {
        return brightness * dmx.dimmerFactor();
    }

    public float getGamma() {
        return gamma;
    }

    public int getTestPattern() {
        return testPattern;
    }

    public float getDiameter() {
        return diameter;
    }

    public float getRadius() {
        return diameter * 0.5F;
    }

    public Direction getFacing() {
        return getBlockState().getValue(SphereScreenBlock.FACING);
    }

    /**
     * Applies GUI config. Also handles the client sync packet path, so every value is
     * re-clamped rather than trusted (see {@link LedPanelBlockEntity#load}).
     */
    public void applyConfig(String source, int pxPerBlock, float brightness, int pattern,
                            float diameter) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.pixelsPerBlock = Clamps.i(pxPerBlock, 8, 1024);
        this.brightness = Clamps.f(brightness, 0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        this.testPattern = Clamps.i(pattern, 0, PATTERN_COUNT - 1);
        this.diameter = Clamps.f(diameter, MIN_DIAMETER, MAX_DIAMETER, DEFAULT_DIAMETER);
        setChanged();
    }

    /** NDI configuration card: switch to live video with the card's source. */
    public void applyNdiCard(String source) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.testPattern = 0;
        setChanged();
    }

    // ------------------------------------------------------------------ DMX (Theatrical)

    @Override
    public ScreenDmxState dmx() {
        return dmx;
    }

    @Override
    public String getDmxModelName() {
        return "NDI Sphere Screen";
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
        boolean changed = dmx.setDimmer(dimmer);
        String slot = dmx.slotForByte(sourceByte);
        if (slot != null && (!slot.equals(sourceName) || testPattern != 0)) {
            sourceName = slot;
            testPattern = 0;
            changed = true;
        }
        if (changed) {
            setChanged();
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
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
        // See RoundScreenBlockEntity: a server chunk unload never calls setRemoved.
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
        tag.putFloat("Diameter", diameter);
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
        gamma = Clamps.f(tag.contains("Gamma") ? tag.getFloat("Gamma") : DEFAULT_GAMMA, 1.0F, 3.0F, DEFAULT_GAMMA);
        testPattern = Clamps.i(tag.contains("Pattern") ? tag.getInt("Pattern") : DEFAULT_PATTERN,
                0, PATTERN_COUNT - 1);
        diameter = Clamps.f(tag.contains("Diameter") ? tag.getFloat("Diameter") : DEFAULT_DIAMETER,
                MIN_DIAMETER, MAX_DIAMETER, DEFAULT_DIAMETER);
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
        return new AABB(worldPosition).inflate(getRadius() + 1.0);
    }
}
