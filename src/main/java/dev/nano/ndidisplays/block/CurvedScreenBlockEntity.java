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
 * Curved LED screen: one mount block whose renderer draws a vertical cylindrical
 * arc of configurable radius, opening angle and height, centred on the block and
 * opening towards its FACING direction. 360 degrees closes the arc into a full
 * cylinder (DJ-booth column); smaller angles give the Coachella-style horseshoe.
 *
 * The video unrolls along the arc: u sweeps the opening angle, v the height, so
 * the full source frame wraps around the curve.
 */
public class CurvedScreenBlockEntity extends BlockEntity implements DmxScreen {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;
    public static final int PATTERN_COUNT = LedPanelBlockEntity.PATTERN_COUNT;

    public static final float MIN_RADIUS = 0.5F;
    public static final float MAX_RADIUS = 16.0F;
    public static final float MIN_ANGLE = 15.0F;
    public static final float MAX_ANGLE = 360.0F;
    public static final float MIN_HEIGHT = 0.5F;
    public static final float MAX_HEIGHT = 16.0F;

    private static final int DEFAULT_PX_PER_BLOCK = 128;
    private static final float DEFAULT_BRIGHTNESS = 0.85F;
    private static final float DEFAULT_GAMMA = 2.2F;
    private static final int DEFAULT_PATTERN = 1;
    private static final float DEFAULT_RADIUS = 3.0F;
    private static final float DEFAULT_ANGLE = 120.0F;
    private static final float DEFAULT_HEIGHT = 3.0F;

    private String sourceName = "";
    private int pixelsPerBlock = DEFAULT_PX_PER_BLOCK;
    private float brightness = DEFAULT_BRIGHTNESS;
    private float gamma = DEFAULT_GAMMA;
    private int testPattern = DEFAULT_PATTERN;
    private float radius = DEFAULT_RADIUS;
    private float arcAngle = DEFAULT_ANGLE;
    private float screenHeight = DEFAULT_HEIGHT;
    /** false = concave (video reads correctly from inside the arc), true = convex (from outside). */
    private boolean convex;

    private final ScreenDmxState dmx = new ScreenDmxState();

    public CurvedScreenBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.CURVED_SCREEN_BE.get(), pos, state);
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

    public float getRadius() {
        return radius;
    }

    public float getArcAngle() {
        return arcAngle;
    }

    public float getScreenHeight() {
        return screenHeight;
    }

    public boolean isConvex() {
        return convex;
    }

    public Direction getFacing() {
        return getBlockState().getValue(CurvedScreenBlock.FACING);
    }

    public void applyConfig(String source, int pxPerBlock, float brightness, int pattern,
                            float radius, float arcAngle, float screenHeight, boolean convex) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.pixelsPerBlock = Clamps.i(pxPerBlock, 8, 1024);
        this.brightness = Clamps.f(brightness, 0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        this.testPattern = Clamps.i(pattern, 0, PATTERN_COUNT - 1);
        this.radius = Clamps.f(radius, MIN_RADIUS, MAX_RADIUS, DEFAULT_RADIUS);
        this.arcAngle = Clamps.f(arcAngle, MIN_ANGLE, MAX_ANGLE, DEFAULT_ANGLE);
        this.screenHeight = Clamps.f(screenHeight, MIN_HEIGHT, MAX_HEIGHT, DEFAULT_HEIGHT);
        this.convex = convex;
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
        return "NDI Curved Screen";
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
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Source", sourceName);
        tag.putInt("PxPerBlock", pixelsPerBlock);
        tag.putFloat("Brightness", brightness);
        tag.putFloat("Gamma", gamma);
        tag.putInt("Pattern", testPattern);
        tag.putFloat("Radius", radius);
        tag.putFloat("ArcAngle", arcAngle);
        tag.putFloat("ScreenHeight", screenHeight);
        tag.putBoolean("Convex", convex);
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
        radius = Clamps.f(tag.contains("Radius") ? tag.getFloat("Radius") : DEFAULT_RADIUS,
                MIN_RADIUS, MAX_RADIUS, DEFAULT_RADIUS);
        arcAngle = Clamps.f(tag.contains("ArcAngle") ? tag.getFloat("ArcAngle") : DEFAULT_ANGLE,
                MIN_ANGLE, MAX_ANGLE, DEFAULT_ANGLE);
        screenHeight = Clamps.f(tag.contains("ScreenHeight") ? tag.getFloat("ScreenHeight") : DEFAULT_HEIGHT,
                MIN_HEIGHT, MAX_HEIGHT, DEFAULT_HEIGHT);
        convex = tag.getBoolean("Convex");
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
        return new AABB(worldPosition).inflate(radius + 1.0, screenHeight * 0.5 + 1.0, radius + 1.0);
    }
}
