package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
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
import java.util.UUID;

/**
 * A motorised stage winch with an LED video tile flown under it — the Freedom Stage
 * "floating sky" element: hundreds of individually suspended LED panels forming one
 * huge video matrix over the audience, each panel free to fly up and down.
 *
 * The panel it carries is not made of blocks: the block entity holds a smooth
 * vertical position (the "drop", in metres below the winch) and the renderer draws
 * the cables and the tile at that height. Motion follows a trapezoidal profile —
 * accelerate, cruise at the working speed, decelerate into the target — exactly like
 * a real stage winch, so DMX height sweeps read as physical movement instead of
 * teleporting blocks.
 *
 * Video: every winch samples its own rectangle of one shared NDI canvas
 * ({@code canvasCols × canvasRows} tiles, this one at {@code canvasCol/canvasRow}),
 * so a bank of winches behaves as a single giant screen that physically decomposes —
 * each tile keeps its slice of the image no matter where it is flown to.
 *
 * DMX (optional, via Theatrical): 4 channels — height coarse, height fine (16-bit,
 * 0 = all the way up, 65535 = all the way down), speed, dimmer.
 */
public class KineticWinchBlockEntity extends BlockEntity {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;
    public static final int PATTERN_COUNT = LedPanelBlockEntity.PATTERN_COUNT;

    /** Longest drop supported, metres of cable. */
    public static final float MAX_DROP_LIMIT = 32.0F;
    /** Fastest working speed selectable, m/s (a WX2512-class winch tops out around here). */
    public static final float MAX_SPEED = 4.0F;
    /** Largest panel dimension, blocks. */
    public static final int MAX_PANEL_SIZE = 8;
    /** Largest video canvas dimension, tiles. */
    public static final int MAX_CANVAS = 64;
    /** DMX footprint: height coarse, height fine, speed, dimmer. */
    public static final int DMX_CHANNEL_COUNT = 4;

    /** Seconds to reach full speed — sets the accel/decel ramp of the motion profile. */
    private static final float ACCEL_TIME = 0.5F;
    private static final float DT = 0.05F;

    public static final int ORIENTATION_FLAT = 0;
    public static final int ORIENTATION_VERTICAL = 1;

    private static final int DEFAULT_PX_PER_BLOCK = 128;
    private static final float DEFAULT_BRIGHTNESS = 0.85F;
    private static final float DEFAULT_GAMMA = 2.2F;
    private static final int DEFAULT_PATTERN = 1;

    private static final UUID NULL_UUID = new UUID(0, 0);

    // --- Video ---
    private String sourceName = "";
    private int pixelsPerBlock = DEFAULT_PX_PER_BLOCK;
    private float brightness = DEFAULT_BRIGHTNESS;
    private float gamma = DEFAULT_GAMMA;
    private int testPattern = DEFAULT_PATTERN;

    // --- Canvas mapping: which slice of the shared video this tile shows ---
    private int canvasCols = 1;
    private int canvasRows = 1;
    private int canvasCol = 0;
    private int canvasRow = 0;

    // --- Panel geometry ---
    private int panelWidth = 2;
    private int panelHeight = 2;
    private int orientation = ORIENTATION_FLAT;
    /** Blow-through mesh tile (~70% open, Tomorrowland style) vs solid cabinet. */
    private boolean mesh = false;

    // --- Motion ---
    private float minDrop = 0.5F;
    private float maxDrop = 7.0F;
    /** Configured working speed, m/s; the DMX speed channel can override it. */
    private float speed = 1.0F;
    private float targetDrop = 0.5F;

    private float currentDrop = 0.5F;
    private float prevDrop = 0.5F;
    private float velocity;
    /** Set after the first NBT load so later sync packets never teleport the tile. */
    private boolean motionInitialized;

    // --- DMX (Theatrical) ---
    private int dmxUniverse = 0;
    private int dmxAddress = 1;
    private UUID networkId = NULL_UUID;
    /** DMX speed channel value; 0 = use the configured working speed. */
    private int dmxSpeed;
    /** DMX dimmer, 0-255; 255 when unpatched so the tile works without a desk. */
    private int dmxDimmer = 255;
    /** RDM device id bytes, generated once by the Theatrical compat layer. */
    @Nullable
    private byte[] dmxDeviceId;
    /** The Theatrical DMXConsumer delegate; opaque so this class never loads Theatrical. */
    @Nullable
    private Object dmxConsumer;

    public KineticWinchBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.KINETIC_WINCH_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------ getters

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
        return brightness * (dmxDimmer / 255.0F);
    }

    public float getGamma() {
        return gamma;
    }

    public int getTestPattern() {
        return testPattern;
    }

    public int getCanvasCols() {
        return canvasCols;
    }

    public int getCanvasRows() {
        return canvasRows;
    }

    public int getCanvasCol() {
        return canvasCol;
    }

    public int getCanvasRow() {
        return canvasRow;
    }

    public int getPanelWidth() {
        return panelWidth;
    }

    public int getPanelHeight() {
        return panelHeight;
    }

    public int getOrientation() {
        return orientation;
    }

    public boolean isMesh() {
        return mesh;
    }

    public float getMinDrop() {
        return minDrop;
    }

    public float getMaxDrop() {
        return maxDrop;
    }

    public float getSpeed() {
        return speed;
    }

    public float getTargetDrop() {
        return targetDrop;
    }

    public float getCurrentDrop() {
        return currentDrop;
    }

    public float getRenderDrop(float partialTick) {
        return prevDrop + (currentDrop - prevDrop) * partialTick;
    }

    public int getDmxUniverse() {
        return dmxUniverse;
    }

    public int getDmxAddress() {
        return dmxAddress;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    @Nullable
    public byte[] getDmxDeviceId() {
        return dmxDeviceId;
    }

    public void setDmxDeviceId(byte[] bytes) {
        this.dmxDeviceId = bytes;
        setChanged();
    }

    @Nullable
    public Object getDmxConsumer() {
        return dmxConsumer;
    }

    public void setDmxConsumer(@Nullable Object consumer) {
        this.dmxConsumer = consumer;
    }

    public Direction getFacing() {
        return getBlockState().getValue(KineticWinchBlock.FACING);
    }

    // ------------------------------------------------------------------ config

    /**
     * Applies GUI config. Also handles the client sync packet path, so every value is
     * re-clamped rather than trusted (see {@link LedPanelBlockEntity#load}).
     */
    public void applyConfig(String source, int pxPerBlock, float brightness, int pattern,
                            int cols, int rows, int col, int row,
                            int panelW, int panelH, int orientation, boolean mesh,
                            float minDrop, float maxDrop, float speed, float targetDrop,
                            int universe, int address, UUID network) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.pixelsPerBlock = Clamps.i(pxPerBlock, 8, 1024);
        this.brightness = Clamps.f(brightness, 0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        this.testPattern = Clamps.i(pattern, 0, PATTERN_COUNT - 1);
        this.canvasCols = Clamps.i(cols, 1, MAX_CANVAS);
        this.canvasRows = Clamps.i(rows, 1, MAX_CANVAS);
        this.canvasCol = Clamps.i(col, 0, this.canvasCols - 1);
        this.canvasRow = Clamps.i(row, 0, this.canvasRows - 1);
        this.panelWidth = Clamps.i(panelW, 1, MAX_PANEL_SIZE);
        this.panelHeight = Clamps.i(panelH, 1, MAX_PANEL_SIZE);
        this.orientation = Clamps.i(orientation, 0, 1);
        this.mesh = mesh;
        this.minDrop = Clamps.f(minDrop, 0.0F, MAX_DROP_LIMIT, 0.5F);
        this.maxDrop = Clamps.f(maxDrop, this.minDrop, MAX_DROP_LIMIT, Math.max(this.minDrop, 7.0F));
        this.speed = Clamps.f(speed, 0.05F, MAX_SPEED, 1.0F);
        this.targetDrop = Clamps.f(targetDrop, this.minDrop, this.maxDrop, this.minDrop);
        this.dmxUniverse = Clamps.i(universe, 0, 32767);
        this.dmxAddress = Clamps.i(address, 1, 512);
        this.networkId = network == null ? NULL_UUID : network;
        setChanged();
    }

    /** NDI configuration card: switch to live video with the card's source. */
    public void applyNdiCard(String source) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.testPattern = 0;
        setChanged();
    }

    /**
     * Theatrical configuration card patch. Caller is responsible for unregistering the
     * DMX consumer before and re-registering it after, since the network may change.
     */
    public void applyDmxPatch(@Nullable UUID network, @Nullable Integer universe, @Nullable Integer address) {
        if (network != null) {
            this.networkId = network;
        }
        if (universe != null) {
            this.dmxUniverse = Clamps.i(universe, 0, 32767);
        }
        if (address != null) {
            this.dmxAddress = Clamps.i(address, 1, 512);
        }
        setChanged();
    }

    // ------------------------------------------------------------------ DMX in

    /**
     * Applies one DMX frame (server side, called by the Theatrical compat consumer).
     * Height is 16-bit like a moving head's pan/tilt: 0 = tile all the way up
     * (minDrop), 65535 = all the way down (maxDrop). Only syncs when something
     * actually changed, so a static desk output costs no packets.
     */
    public void applyDmx(int height16, int speedByte, int dimmer) {
        float span = maxDrop - minDrop;
        float newTarget = minDrop + (height16 / 65535.0F) * span;
        boolean changed = Math.abs(newTarget - targetDrop) > 0.001F
                || speedByte != dmxSpeed
                || dimmer != dmxDimmer;
        if (!changed) {
            return;
        }
        targetDrop = newTarget;
        dmxSpeed = speedByte;
        dmxDimmer = dimmer;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /** Working speed for this move: DMX speed channel overrides the configured speed. */
    private float effectiveSpeed() {
        if (dmxSpeed <= 0) {
            return speed;
        }
        return Math.max(0.05F, (dmxSpeed / 255.0F) * MAX_SPEED);
    }

    // ------------------------------------------------------------------ motion

    /**
     * Runs on both sides: the server for authority and saves, the client for smooth
     * 60 fps motion (only the target is synced; both sides integrate the same profile,
     * and the renderer lerps between ticks).
     */
    public static void tick(Level level, BlockPos pos, BlockState state, KineticWinchBlockEntity be) {
        be.prevDrop = be.currentDrop;

        float target = Clamps.f(be.targetDrop, be.minDrop, be.maxDrop, be.minDrop);
        float dist = target - be.currentDrop;
        if (Math.abs(dist) < 1e-4F && Math.abs(be.velocity) < 1e-3F) {
            be.currentDrop = target;
            be.velocity = 0;
            return;
        }

        float maxV = be.effectiveSpeed();
        float accel = maxV / ACCEL_TIME;
        float dir = Math.signum(dist);
        // Decelerate once the remaining distance is what the current speed needs to stop in.
        float stopDist = (be.velocity * be.velocity) / (2 * accel);
        float desired = (Math.signum(be.velocity) == dir && stopDist >= Math.abs(dist))
                ? 0 : dir * maxV;

        if (be.velocity < desired) {
            be.velocity = Math.min(be.velocity + accel * DT, desired);
        } else if (be.velocity > desired) {
            be.velocity = Math.max(be.velocity - accel * DT, desired);
        }

        float step = be.velocity * DT;
        if (Math.signum(step) == dir && Math.abs(step) >= Math.abs(dist)) {
            be.currentDrop = target;
            be.velocity = 0;
        } else {
            be.currentDrop += step;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && !level.isClientSide) {
            TheatricalCompat.register(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            TheatricalCompat.unregister(this);
        }
        super.setRemoved();
    }

    // ------------------------------------------------------------------ NBT / sync

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Source", sourceName);
        tag.putInt("PxPerBlock", pixelsPerBlock);
        tag.putFloat("Brightness", brightness);
        tag.putFloat("Gamma", gamma);
        tag.putInt("Pattern", testPattern);
        tag.putInt("CanvasCols", canvasCols);
        tag.putInt("CanvasRows", canvasRows);
        tag.putInt("CanvasCol", canvasCol);
        tag.putInt("CanvasRow", canvasRow);
        tag.putInt("PanelW", panelWidth);
        tag.putInt("PanelH", panelHeight);
        tag.putInt("Orientation", orientation);
        tag.putBoolean("Mesh", mesh);
        tag.putFloat("MinDrop", minDrop);
        tag.putFloat("MaxDrop", maxDrop);
        tag.putFloat("Speed", speed);
        tag.putFloat("TargetDrop", targetDrop);
        tag.putFloat("CurrentDrop", currentDrop);
        tag.putInt("DmxUniverse", dmxUniverse);
        tag.putInt("DmxAddress", dmxAddress);
        tag.putUUID("DmxNetwork", networkId);
        tag.putInt("DmxDimmer", dmxDimmer);
        tag.putInt("DmxSpeed", dmxSpeed);
        if (dmxDeviceId != null) {
            tag.putByteArray("DmxDeviceId", dmxDeviceId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        sourceName = Clamps.name(tag.getString("Source"), MAX_SOURCE_NAME);
        pixelsPerBlock = Clamps.i(tag.contains("PxPerBlock") ? tag.getInt("PxPerBlock") : DEFAULT_PX_PER_BLOCK, 8, 1024);
        brightness = Clamps.f(tag.contains("Brightness") ? tag.getFloat("Brightness") : DEFAULT_BRIGHTNESS,
                0.02F, 1.0F, DEFAULT_BRIGHTNESS);
        gamma = Clamps.f(tag.contains("Gamma") ? tag.getFloat("Gamma") : DEFAULT_GAMMA, 1.0F, 3.0F, DEFAULT_GAMMA);
        testPattern = Clamps.i(tag.contains("Pattern") ? tag.getInt("Pattern") : DEFAULT_PATTERN, 0, PATTERN_COUNT - 1);
        canvasCols = Clamps.i(tag.contains("CanvasCols") ? tag.getInt("CanvasCols") : 1, 1, MAX_CANVAS);
        canvasRows = Clamps.i(tag.contains("CanvasRows") ? tag.getInt("CanvasRows") : 1, 1, MAX_CANVAS);
        canvasCol = Clamps.i(tag.getInt("CanvasCol"), 0, canvasCols - 1);
        canvasRow = Clamps.i(tag.getInt("CanvasRow"), 0, canvasRows - 1);
        panelWidth = Clamps.i(tag.contains("PanelW") ? tag.getInt("PanelW") : 2, 1, MAX_PANEL_SIZE);
        panelHeight = Clamps.i(tag.contains("PanelH") ? tag.getInt("PanelH") : 2, 1, MAX_PANEL_SIZE);
        orientation = Clamps.i(tag.getInt("Orientation"), 0, 1);
        mesh = tag.getBoolean("Mesh");
        minDrop = Clamps.f(tag.contains("MinDrop") ? tag.getFloat("MinDrop") : 0.5F, 0.0F, MAX_DROP_LIMIT, 0.5F);
        maxDrop = Clamps.f(tag.contains("MaxDrop") ? tag.getFloat("MaxDrop") : 7.0F, minDrop, MAX_DROP_LIMIT, 7.0F);
        speed = Clamps.f(tag.contains("Speed") ? tag.getFloat("Speed") : 1.0F, 0.05F, MAX_SPEED, 1.0F);
        targetDrop = Clamps.f(tag.getFloat("TargetDrop"), minDrop, maxDrop, minDrop);
        dmxUniverse = Clamps.i(tag.getInt("DmxUniverse"), 0, 32767);
        dmxAddress = Clamps.i(tag.contains("DmxAddress") ? tag.getInt("DmxAddress") : 1, 1, 512);
        networkId = tag.hasUUID("DmxNetwork") ? tag.getUUID("DmxNetwork") : NULL_UUID;
        dmxDimmer = Clamps.i(tag.contains("DmxDimmer") ? tag.getInt("DmxDimmer") : 255, 0, 255);
        dmxSpeed = Clamps.i(tag.getInt("DmxSpeed"), 0, 255);
        if (tag.contains("DmxDeviceId")) {
            dmxDeviceId = tag.getByteArray("DmxDeviceId");
        }

        // First load (world load / chunk arrival) snaps the tile to its saved position;
        // later sync packets only carry a new target, and the local motion profile
        // flies there instead of teleporting.
        if (!motionInitialized) {
            float saved = tag.contains("CurrentDrop") ? tag.getFloat("CurrentDrop") : targetDrop;
            currentDrop = Clamps.f(saved, 0.0F, MAX_DROP_LIMIT, targetDrop);
            prevDrop = currentDrop;
            velocity = 0;
            motionInitialized = true;
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
        double reach = Math.max(panelWidth, panelHeight) + 1.0;
        return new AABB(worldPosition)
                .inflate(reach, 0, reach)
                .expandTowards(0, -(maxDrop + panelHeight + 1.0), 0);
    }
}
