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
 *
 * Twin mode: the two suspension cables become two independent motors (Winch A on the
 * tile's right-hand attachment, Winch B on the left), each flying its own trapezoidal
 * profile. The tile tilts to follow the height difference — same UV slice of the
 * canvas, only the 3D transform changes — which is what lets a desk run tilt waves
 * across an array, not just vertical ones. The DMX footprint grows to 6 channels
 * (A coarse/fine, B coarse/fine, speed, dimmer); LINKED keeps the default 4.
 */
public class KineticWinchBlockEntity extends BlockEntity {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;
    public static final int PATTERN_COUNT = LedPanelBlockEntity.PATTERN_COUNT;

    /** Longest drop supported, metres of cable. */
    public static final float MAX_DROP_LIMIT = 32.0F;
    /** Fastest working speed selectable, m/s (a WX2512-class winch tops out around here). */
    public static final float MAX_SPEED = 4.0F;
    /**
     * Largest flown tile dimension, blocks.
     *
     * The tile is a single quad, not a grid of blocks, so its size costs nothing to render —
     * the only thing that scales with it is the render bounding box. That makes a generous
     * limit cheap, and 8 was too small for a tile meant to read as one panel of a stadium-scale
     * flying matrix.
     */
    public static final int MAX_PANEL_SIZE = 32;
    /** Largest video canvas dimension, tiles. */
    public static final int MAX_CANVAS = 64;
    /** DMX footprint in LINKED mode: height coarse, height fine, speed, dimmer. */
    public static final int DMX_CHANNEL_COUNT = 4;
    /** DMX footprint in TWIN mode: A coarse/fine, B coarse/fine, speed, dimmer. */
    public static final int DMX_CHANNEL_COUNT_TWIN = 6;
    /** Steepest tilt limit configurable, degrees from horizontal. */
    public static final float MAX_TILT_LIMIT = 45.0F;
    private static final float DEFAULT_MAX_TILT = 15.0F;

    // --- Payload kinds: what hangs from the hook ---
    /** The LED video tile (default, the floating-sky element). */
    public static final int PAYLOAD_LED_TILE = 0;
    /** Kinetic-lights RGB sphere on a single cable. */
    public static final int PAYLOAD_KINETIC_SPHERE = 1;
    /** Rotating mirror ball, pure decor (no extra DMX channels). */
    public static final int PAYLOAD_MIRROR_BALL = 2;
    /** A Theatrical / Extra Lights fixture flown on the hook. */
    public static final int PAYLOAD_FIXTURE = 3;
    /** Vertical LED slat / kinetic curtain blade on a single cable. */
    public static final int PAYLOAD_SLAT = 4;
    public static final int PAYLOAD_COUNT = 5;

    /** Sphere footprint: height 16-bit, speed, dimmer, R, G, B. */
    public static final int DMX_CHANNEL_COUNT_SPHERE = 7;
    /** Flown fixture footprint: height 16-bit, speed, intensity, R, G, B, focus, pan, tilt. */
    public static final int DMX_CHANNEL_COUNT_FIXTURE = 10;

    // --- Flown-fixture DMX modes ---
    //
    // Deliberately nested: every mode is a prefix of the next, so channel N means the same
    // thing whichever mode is patched. That is how real fixture mode tables are built, and it
    // means switching mode never re-shuffles the channels a desk has already been programmed
    // against — it only adds or removes control from the tail.
    //
    // Channels a mode omits are not zeroed; they hold their last value, exactly as a real
    // head does when patched in a smaller mode. Fresh winches default to white, centred
    // pan/tilt, so a fixture flown in BASIC still lights.

    /** Position only, 4ch: height coarse/fine, speed, intensity. */
    public static final int FIXTURE_MODE_BASIC = 0;
    /** Colour, 7ch: BASIC + R, G, B. */
    public static final int FIXTURE_MODE_COLOUR = 1;
    /** Full, 10ch: COLOUR + focus, pan, tilt. */
    public static final int FIXTURE_MODE_FULL = 2;
    public static final int FIXTURE_MODE_COUNT = 3;

    /** Channel count for each flown-fixture mode, indexed by mode id. */
    private static final int[] FIXTURE_MODE_CHANNELS = {4, 7, DMX_CHANNEL_COUNT_FIXTURE};

    /** Moving-head sweep speeds for the flown fixture's pan/tilt interpolation. */
    private static final float PAN_RANGE = 540.0F;
    private static final float TILT_RANGE = 270.0F;
    private static final float PAN_SPEED = 240.0F;
    private static final float TILT_SPEED = 200.0F;

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
    /**
     * TWIN: the two cables are independent motors and the tile tilts to follow their
     * height difference. LINKED (default): motor A drives both attachments.
     */
    private boolean twinMode;
    /** Steepest tilt allowed in twin mode, degrees; B's target is clamped around A's. */
    private float maxTilt = DEFAULT_MAX_TILT;

    // Motor A — also the only motor in LINKED mode. Field names kept for NBT compat.
    private float targetDrop = 0.5F;
    private float currentDrop = 0.5F;
    private float prevDrop = 0.5F;
    private float velocity;
    // Motor B — mirrors A while LINKED so switching modes never jumps the tile.
    private float targetDropB = 0.5F;
    private float currentDropB = 0.5F;
    private float prevDropB = 0.5F;
    private float velocityB;
    /** Set after the first NBT load so later sync packets never teleport the tile. */
    private boolean motionInitialized;

    // --- Payload ---
    private int payload = PAYLOAD_LED_TILE;
    /** Registry id of the Theatrical fixture block flown in PAYLOAD_FIXTURE mode. */
    private String fixtureBlockId = "";
    /** Kinetic-sphere colour, DMX-driven (255/255/255 when unpatched). */
    private int dmxRed = 255;
    private int dmxGreen = 255;
    private int dmxBlue = 255;
    // Flown fixture head state. Raw DMX bytes are the targets; the client sweeps the
    // head towards them at moving-head speeds, like Theatrical's own renderers.
    // Intensity starts open (like the tile dimmer) so a freshly hung fixture emits
    // light without a desk; the first DMX frame takes over.
    private int fixIntensity = 255;
    private int fixRed = 255;
    private int fixGreen = 255;
    private int fixBlue = 255;
    /** 0 = tight beam at rest, like Theatrical's fixtures; the desk opens it. */
    private int fixFocus;
    private int fixPan = 128;
    private int fixTilt = 128;
    /** Which flown-fixture DMX mode is patched. Defaults to the full 10-channel profile. */
    private int fixtureMode = FIXTURE_MODE_FULL;
    private float curPan;
    private float curTilt;
    private float prevPan;
    private float prevTilt;
    private boolean headInitialized;

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

    public boolean isTwinMode() {
        return twinMode;
    }

    public float getMaxTilt() {
        return maxTilt;
    }

    public float getTargetDropB() {
        return twinMode ? targetDropB : targetDrop;
    }

    /** Winch B's interpolated drop; in LINKED mode it is simply motor A's. */
    public float getRenderDropB(float partialTick) {
        if (!twinMode) {
            return getRenderDrop(partialTick);
        }
        return prevDropB + (currentDropB - prevDropB) * partialTick;
    }

    /**
     * Horizontal distance between the two cable attachment points, metres — the lever
     * arm the A/B height difference tilts the tile over. Must match the renderer's
     * cable inset so the drawn tilt equals the physical geometry.
     */
    public float cableSpan() {
        float inset = Math.min(0.2F, panelWidth * 0.25F);
        return Math.max(0.1F, panelWidth - 2.0F * inset);
    }

    /** Largest A/B drop difference the configured tilt limit allows. */
    private float maxDropDifference() {
        return (float) Math.tan(Math.toRadians(Clamps.f(maxTilt, 0.0F, MAX_TILT_LIMIT, DEFAULT_MAX_TILT)))
                * cableSpan();
    }

    public int getPayload() {
        return payload;
    }

    public String getFixtureBlockId() {
        return fixtureBlockId;
    }

    /** Kinetic-sphere colour with the DMX dimmer folded in, 0-1 per component. */
    public float[] getSphereColor() {
        float dim = dmxDimmer / 255.0F;
        return new float[]{dmxRed / 255.0F * dim, dmxGreen / 255.0F * dim, dmxBlue / 255.0F * dim};
    }

    /** Flown fixture beam colour (intensity NOT folded in), 0-1 per component. */
    public float[] getFixtureColor() {
        return new float[]{fixRed / 255.0F, fixGreen / 255.0F, fixBlue / 255.0F};
    }

    public float getFixtureIntensity() {
        return fixIntensity / 255.0F;
    }

    public float getFixtureFocus() {
        return fixFocus / 255.0F;
    }

    public float getRenderPan(float partialTick) {
        return prevPan + (curPan - prevPan) * partialTick;
    }

    public float getRenderTilt(float partialTick) {
        return prevTilt + (curTilt - prevTilt) * partialTick;
    }

    private static float panTargetDegrees(int panByte) {
        return (panByte / 255.0F - 0.5F) * PAN_RANGE;
    }

    private static float tiltTargetDegrees(int tiltByte) {
        return (tiltByte / 255.0F - 0.5F) * TILT_RANGE;
    }

    /** Sets the flown fixture by registry id (right-click the winch with the block item). */
    public void setFixturePayload(String blockId) {
        this.payload = PAYLOAD_FIXTURE;
        this.fixtureBlockId = Clamps.name(blockId, 256);
        // A freshly hung fixture lights up without a desk; DMX takes over on patch.
        this.fixIntensity = 255;
        setChanged();
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
                            boolean twinMode, float maxTilt, float targetDropB,
                            int payload, int fixtureMode,
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
        this.twinMode = twinMode;
        this.maxTilt = Clamps.f(maxTilt, 0.0F, MAX_TILT_LIMIT, DEFAULT_MAX_TILT);
        this.targetDropB = clampTargetB(Clamps.f(targetDropB, this.minDrop, this.maxDrop, this.targetDrop));
        this.payload = Clamps.i(payload, 0, PAYLOAD_COUNT - 1);
        if (this.payload == PAYLOAD_SLAT) {
            // One blade, one motor — never a twin tilt footprint.
            this.twinMode = false;
        }
        this.fixtureMode = Clamps.i(fixtureMode, 0, 63);
        this.dmxUniverse = Clamps.i(universe, 0, 32767);
        this.dmxAddress = Clamps.i(address, 1, 512);
        this.networkId = network == null ? NULL_UUID : network;
        setChanged();
    }

    /** B's target held within the tilt limit around A's — A is the master motor. */
    private float clampTargetB(float wanted) {
        return clampTargetB(wanted, targetDrop);
    }

    private float clampTargetB(float wanted, float aTarget) {
        float maxDiff = maxDropDifference();
        return Clamps.f(wanted, Math.max(minDrop, aTarget - maxDiff),
                Math.min(maxDrop, aTarget + maxDiff), aTarget);
    }

    /** NDI configuration card: switch to live video with the card's source. */
    public void applyNdiCard(String source) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.testPattern = 0;
        setChanged();
    }

    /**
     * Assigns this winch's slice of a shared canvas from a physical park layout
     * (region apply / auto-map).
     */
    public void applyCanvasMapping(int cols, int rows, int col, int row) {
        this.canvasCols = Clamps.i(cols, 1, MAX_CANVAS);
        this.canvasRows = Clamps.i(rows, 1, MAX_CANVAS);
        this.canvasCol = Clamps.i(col, 0, this.canvasCols - 1);
        this.canvasRow = Clamps.i(row, 0, this.canvasRows - 1);
        setChanged();
    }

    /**
     * NDI configuration card motor-mode override. Entering TWIN seeds motor B on A's
     * position so the tile never jumps; back to LINKED simply re-slaves B (the tick
     * mirrors it). Caller re-registers the DMX consumer — the footprint moves 4↔6 ch.
     */
    public void applyCardWinchMode(boolean twin) {
        if (this.twinMode == twin) {
            return;
        }
        this.twinMode = twin;
        if (twin) {
            targetDropB = targetDrop;
        }
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

    /**
     * TWIN-mode DMX frame: two independent 16-bit heights over the same min→max
     * envelope. B is clamped within the tilt limit around A, so a desk cannot fold
     * the tile past what the rig allows — exactly like a motion controller's safety
     * envelope.
     */
    public void applyDmxTwin(int heightA16, int heightB16, int speedByte, int dimmer) {
        float span = maxDrop - minDrop;
        float newTargetA = minDrop + (heightA16 / 65535.0F) * span;
        float newTargetB = clampTargetB(minDrop + (heightB16 / 65535.0F) * span, newTargetA);
        boolean changed = Math.abs(newTargetA - targetDrop) > 0.001F
                || Math.abs(newTargetB - targetDropB) > 0.001F
                || speedByte != dmxSpeed
                || dimmer != dmxDimmer;
        if (!changed) {
            return;
        }
        targetDrop = newTargetA;
        targetDropB = newTargetB;
        dmxSpeed = speedByte;
        dmxDimmer = dimmer;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /**
     * Kinetic-sphere DMX frame: height + speed + dimmer as usual, plus the sphere's
     * RGB colour — the classic kinetic-lights footprint.
     */
    public void applyDmxSphere(int height16, int speedByte, int dimmer, int r, int g, int b) {
        float span = maxDrop - minDrop;
        float newTarget = minDrop + (height16 / 65535.0F) * span;
        boolean changed = Math.abs(newTarget - targetDrop) > 0.001F
                || speedByte != dmxSpeed || dimmer != dmxDimmer
                || r != dmxRed || g != dmxGreen || b != dmxBlue;
        if (!changed) {
            return;
        }
        targetDrop = newTarget;
        dmxSpeed = speedByte;
        dmxDimmer = dimmer;
        dmxRed = r;
        dmxGreen = g;
        dmxBlue = b;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /**
     * Flown-fixture DMX frame: winch height + speed, then the head — intensity, RGB,
     * focus, pan, tilt. Pan/tilt land as targets; both sides sweep the head towards
     * them at moving-head speeds, so desk moves read as physical motion.
     */
    /**
     * BASIC mode (4ch): position and intensity only. Colour, focus and pan/tilt hold
     * whatever they were, so a fixture patched small stays where it was pointed.
     */
    public void applyDmxFixture(int height16, int speedByte, int intensity) {
        applyDmxFixture(height16, speedByte, intensity,
                fixRed, fixGreen, fixBlue, fixFocus, fixPan, fixTilt);
    }

    /** COLOUR mode (7ch): position, intensity and colour; focus and pan/tilt hold. */
    public void applyDmxFixture(int height16, int speedByte, int intensity, int r, int g, int b) {
        applyDmxFixture(height16, speedByte, intensity, r, g, b, fixFocus, fixPan, fixTilt);
    }

    /**
     * Applies a frame whose channel meanings came from the fixture's own personality. Any
     * value of -1 means the selected mode has no channel for it, so it holds — the same
     * behaviour as a real head patched in a mode that omits a control.
     */
    public void applyDmxFixtureMapped(int height16, int speedByte, int intensity,
                                      int r, int g, int b, int focus, int pan, int tilt) {
        applyDmxFixture(height16, speedByte,
                intensity < 0 ? fixIntensity : intensity,
                r < 0 ? fixRed : r,
                g < 0 ? fixGreen : g,
                b < 0 ? fixBlue : b,
                focus < 0 ? fixFocus : focus,
                pan < 0 ? fixPan : pan,
                tilt < 0 ? fixTilt : tilt);
    }

    /** FULL mode (10ch). The canonical path — the shorter modes delegate here. */
    public void applyDmxFixture(int height16, int speedByte, int intensity,
                                int r, int g, int b, int focus, int pan, int tilt) {
        float span = maxDrop - minDrop;
        float newTarget = minDrop + (height16 / 65535.0F) * span;
        boolean changed = Math.abs(newTarget - targetDrop) > 0.001F
                || speedByte != dmxSpeed
                || intensity != fixIntensity || r != fixRed || g != fixGreen || b != fixBlue
                || focus != fixFocus || pan != fixPan || tilt != fixTilt;
        if (!changed) {
            return;
        }
        targetDrop = newTarget;
        dmxSpeed = speedByte;
        fixIntensity = intensity;
        fixRed = r;
        fixGreen = g;
        fixBlue = b;
        fixFocus = focus;
        fixPan = pan;
        fixTilt = tilt;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /**
     * This winch's DMX footprint, by payload: LED tile 4 (LINKED) or 6 (TWIN),
     * kinetic sphere 7, flown fixture 4/7/10 by its mode, mirror ball 4 (height/speed only).
     */
    public int getDmxChannelCount() {
        return switch (payload) {
            case PAYLOAD_KINETIC_SPHERE -> DMX_CHANNEL_COUNT_SPHERE;
            case PAYLOAD_FIXTURE -> fixtureFootprint();
            case PAYLOAD_MIRROR_BALL, PAYLOAD_SLAT -> DMX_CHANNEL_COUNT;
            default -> twinMode ? DMX_CHANNEL_COUNT_TWIN : DMX_CHANNEL_COUNT;
        };
    }

    /**
     * The patched flown-fixture mode. An index into {@link #fixturePersonalities()} when the
     * flown fixture declares its own modes, otherwise one of the generic
     * {@link #FIXTURE_MODE_BASIC}/COLOUR/FULL footprints.
     */
    public int getFixtureMode() {
        return fixtureMode;
    }

    /** Channels the winch itself always occupies ahead of the fixture: height 16-bit + speed. */
    public static final int WINCH_LEAD_CHANNELS = 3;

    /**
     * Footprint of a flown fixture: the winch's own height/speed channels plus whatever the
     * fixture's selected mode occupies. Falls back to the generic nested footprints when the
     * fixture declares no modes of its own.
     */
    private int fixtureFootprint() {
        dev.nano.ndidisplays.compat.theatrical.FixturePersonality p = activePersonality();
        if (p != null) {
            return WINCH_LEAD_CHANNELS + p.channelCount();
        }
        return FIXTURE_MODE_CHANNELS[Math.floorMod(fixtureMode, FIXTURE_MODE_CHANNELS.length)];
    }

    /**
     * The DMX modes the flown fixture declares, or empty when it declares none (no
     * Theatrical, not a fixture, nothing hung yet). Reading the fixture's own personalities
     * means the winch offers exactly the modes the real head has, named as it names them,
     * instead of a generic profile that happens to be close.
     */
    public java.util.List<dev.nano.ndidisplays.compat.theatrical.FixturePersonality> fixturePersonalities() {
        if (payload != PAYLOAD_FIXTURE) {
            return java.util.List.of();
        }
        return dev.nano.ndidisplays.compat.theatrical.TheatricalCompat
                .fixturePersonalities(fixtureBlockId);
    }

    /** The selected personality, or null when falling back to the generic footprints. */
    @Nullable
    public dev.nano.ndidisplays.compat.theatrical.FixturePersonality activePersonality() {
        java.util.List<dev.nano.ndidisplays.compat.theatrical.FixturePersonality> list =
                fixturePersonalities();
        if (list.isEmpty()) {
            return null;
        }
        return list.get(Math.floorMod(fixtureMode, list.size()));
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
        be.prevDropB = be.currentDropB;

        float maxV = be.effectiveSpeed();
        float[] a = step(be.currentDrop, be.velocity,
                Clamps.f(be.targetDrop, be.minDrop, be.maxDrop, be.minDrop), maxV);
        be.currentDrop = a[0];
        be.velocity = a[1];

        if (be.twinMode) {
            float[] b = step(be.currentDropB, be.velocityB,
                    Clamps.f(be.targetDropB, be.minDrop, be.maxDrop, be.minDrop), maxV);
            be.currentDropB = b[0];
            be.velocityB = b[1];
        } else {
            // Mirror A so a later switch to TWIN starts level instead of jumping.
            be.currentDropB = be.currentDrop;
            be.prevDropB = be.prevDrop;
            be.targetDropB = be.targetDrop;
            be.velocityB = 0;
        }

        if (be.payload == PAYLOAD_FIXTURE) {
            be.stepHead();
        }
    }

    /** Sweeps the flown fixture's head towards its DMX pan/tilt targets. */
    private void stepHead() {
        prevPan = curPan;
        prevTilt = curTilt;
        curPan = approach(curPan, panTargetDegrees(fixPan), PAN_SPEED * DT);
        curTilt = approach(curTilt, tiltTargetDegrees(fixTilt), TILT_SPEED * DT);
    }

    private static float approach(float current, float target, float maxStep) {
        float diff = target - current;
        if (Math.abs(diff) <= maxStep) {
            return target;
        }
        return current + Math.signum(diff) * maxStep;
    }

    /** One 50 ms integration of the trapezoidal profile; returns {position, velocity}. */
    private static float[] step(float current, float velocity, float target, float maxV) {
        float dist = target - current;
        if (Math.abs(dist) < 1e-4F && Math.abs(velocity) < 1e-3F) {
            return new float[]{target, 0};
        }

        float accel = maxV / ACCEL_TIME;
        float dir = Math.signum(dist);
        // Decelerate once the remaining distance is what the current speed needs to stop in.
        float stopDist = (velocity * velocity) / (2 * accel);
        float desired = (Math.signum(velocity) == dir && stopDist >= Math.abs(dist))
                ? 0 : dir * maxV;

        if (velocity < desired) {
            velocity = Math.min(velocity + accel * DT, desired);
        } else if (velocity > desired) {
            velocity = Math.max(velocity - accel * DT, desired);
        }

        float step = velocity * DT;
        if (Math.signum(step) == dir && Math.abs(step) >= Math.abs(dist)) {
            return new float[]{target, 0};
        }
        return new float[]{current + step, velocity};
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

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        // Server chunk unload never calls setRemoved, so without this the DMX consumer — which
        // holds this block entity — stayed registered in Theatrical's network for the whole
        // session, and DMX arriving for the unloaded screen force-loaded the chunk on every
        // frame the desk sent. A reload re-registers through setLevel.
        if (level != null && !level.isClientSide) {
            TheatricalCompat.unregister(this);
        }
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
        tag.putBoolean("TwinMode", twinMode);
        tag.putFloat("MaxTilt", maxTilt);
        tag.putFloat("TargetDrop", targetDrop);
        tag.putFloat("CurrentDrop", currentDrop);
        tag.putFloat("TargetDropB", targetDropB);
        tag.putFloat("CurrentDropB", currentDropB);
        tag.putInt("Payload", payload);
        tag.putInt("FixtureMode", fixtureMode);
        tag.putString("FixtureBlock", fixtureBlockId);
        tag.putInt("DmxRed", dmxRed);
        tag.putInt("DmxGreen", dmxGreen);
        tag.putInt("DmxBlue", dmxBlue);
        tag.putInt("FixIntensity", fixIntensity);
        // Marker: this build treats a saved intensity of 0 as a real desk value.
        // Its absence identifies pre-fix saves whose 0 default must migrate to open.
        tag.putBoolean("FixMk", true);
        tag.putInt("FixRed", fixRed);
        tag.putInt("FixGreen", fixGreen);
        tag.putInt("FixBlue", fixBlue);
        tag.putInt("FixFocus", fixFocus);
        tag.putInt("FixPan", fixPan);
        tag.putInt("FixTilt", fixTilt);
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
        twinMode = tag.getBoolean("TwinMode");
        maxTilt = Clamps.f(tag.contains("MaxTilt") ? tag.getFloat("MaxTilt") : DEFAULT_MAX_TILT,
                0.0F, MAX_TILT_LIMIT, DEFAULT_MAX_TILT);
        targetDrop = Clamps.f(tag.getFloat("TargetDrop"), minDrop, maxDrop, minDrop);
        targetDropB = Clamps.f(tag.contains("TargetDropB") ? tag.getFloat("TargetDropB") : targetDrop,
                minDrop, maxDrop, targetDrop);
        payload = Clamps.i(tag.getInt("Payload"), 0, PAYLOAD_COUNT - 1);
        if (tag.contains("FixtureMode")) {
            fixtureMode = Clamps.i(tag.getInt("FixtureMode"), 0, 63);
        }
        fixtureBlockId = Clamps.name(tag.getString("FixtureBlock"), 256);
        dmxRed = Clamps.i(tag.contains("DmxRed") ? tag.getInt("DmxRed") : 255, 0, 255);
        dmxGreen = Clamps.i(tag.contains("DmxGreen") ? tag.getInt("DmxGreen") : 255, 0, 255);
        dmxBlue = Clamps.i(tag.contains("DmxBlue") ? tag.getInt("DmxBlue") : 255, 0, 255);
        // Old saves (no FixMk marker) defaulted the intensity to 0, leaving every
        // fixture hung with them dark forever: migrate those zeros to open. Tags
        // carrying the marker — including every DMX sync packet — keep 0 as a real
        // desk value, otherwise blackout would be impossible.
        int savedFixIntensity = tag.contains("FixIntensity") ? tag.getInt("FixIntensity") : 255;
        if (savedFixIntensity == 0 && !tag.contains("FixMk")) {
            savedFixIntensity = 255;
        }
        fixIntensity = Clamps.i(savedFixIntensity, 0, 255);
        fixRed = Clamps.i(tag.contains("FixRed") ? tag.getInt("FixRed") : 255, 0, 255);
        fixGreen = Clamps.i(tag.contains("FixGreen") ? tag.getInt("FixGreen") : 255, 0, 255);
        fixBlue = Clamps.i(tag.contains("FixBlue") ? tag.getInt("FixBlue") : 255, 0, 255);
        fixFocus = Clamps.i(tag.getInt("FixFocus"), 0, 255);
        fixPan = Clamps.i(tag.contains("FixPan") ? tag.getInt("FixPan") : 128, 0, 255);
        fixTilt = Clamps.i(tag.contains("FixTilt") ? tag.getInt("FixTilt") : 128, 0, 255);
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
            float savedB = tag.contains("CurrentDropB") ? tag.getFloat("CurrentDropB") : targetDropB;
            currentDropB = Clamps.f(savedB, 0.0F, MAX_DROP_LIMIT, targetDropB);
            prevDropB = currentDropB;
            velocityB = 0;
            motionInitialized = true;
        }
        if (!headInitialized) {
            curPan = panTargetDegrees(fixPan);
            curTilt = tiltTargetDegrees(fixTilt);
            prevPan = curPan;
            prevTilt = curTilt;
            headInitialized = true;
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
