package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An NDI router: publishes its own NDI output name and transparently forwards whichever
 * source is patched to it.
 *
 * This is NDI's routing primitive ({@code NDIlib_routing_*}), not a re-broadcast — nothing
 * is decoded or re-encoded, so a router costs essentially nothing regardless of resolution.
 * Receivers subscribe to the stable router name and the operator repatches the source behind
 * it, exactly like a hardware router's output bus.
 */
public class NdiRouterBlockEntity extends BlockEntity {

    /** Matches the wire limit in {@code UpdateRouterConfigPacket} and the GUI edit boxes. */
    public static final int MAX_NAME = 128;

    /** The name this router publishes on the network. */
    private String outputName = "";
    /** The source currently patched to the output; blank means the output goes idle. */
    private String sourceName = "";
    /**
     * Test pattern to generate instead of repatching, or
     * {@link dev.nano.ndidisplays.client.ndi.TestPatternGenerator#PATTERN_OFF} to pass a real
     * source through. A router that can generate is what lets an operator prove the network and
     * the receivers before any camera exists.
     */
    private int pattern = dev.nano.ndidisplays.client.ndi.TestPatternGenerator.PATTERN_OFF;

    /**
     * Format of generated frames. Only meaningful while a pattern is selected — a router that is
     * forwarding has no format of its own, it passes on whatever the upstream source is.
     *
     * These were hardcoded at 1280x720/15 when patterns were added, which made a generated feed
     * silently different from everything else on the network and impossible to match to the wall
     * it was testing.
     */
    private int patternResolution = DEFAULT_PATTERN_RESOLUTION;
    private int patternFps = DEFAULT_PATTERN_FPS;

    /** Output formats for generated patterns, shared with the cameras' familiar set. */
    public static final int[] RES_W = {1280, 1920, 2560};
    public static final int[] RES_H = {720, 1080, 1440};
    private static final int DEFAULT_PATTERN_RESOLUTION = 1;   // 1920x1080
    private static final int DEFAULT_PATTERN_FPS = 30;

    public NdiRouterBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.ROUTER_BE.get(), pos, state);
    }

    public Direction getFacing() {
        return getBlockState().getValue(NdiRouterBlock.FACING);
    }

    public String getOutputName() {
        return outputName;
    }

    public String getSourceName() {
        return sourceName;
    }

    /** The published NDI name, with a per-position default when the operator has not set one. */
    public String getEffectiveOutputName() {
        if (!outputName.isBlank()) {
            return outputName;
        }
        return "MC Router " + worldPosition.getX() + "," + worldPosition.getY()
                + "," + worldPosition.getZ();
    }

    public int getPattern() {
        return pattern;
    }

    /** True when this router generates its own picture rather than forwarding one. */
    public boolean isGenerating() {
        return pattern != dev.nano.ndidisplays.client.ndi.TestPatternGenerator.PATTERN_OFF;
    }

    public int getPatternResolution() {
        return patternResolution;
    }

    public int getPatternWidth() {
        return RES_W[Math.floorMod(patternResolution, RES_W.length)];
    }

    public int getPatternHeight() {
        return RES_H[Math.floorMod(patternResolution, RES_H.length)];
    }

    public int getPatternFps() {
        return patternFps;
    }

    public void applyConfig(String outputName, String sourceName, int pattern,
                            int patternResolution, int patternFps) {
        this.patternResolution = Clamps.i(patternResolution, 0, RES_W.length - 1);
        this.patternFps = Clamps.i(patternFps, 1, 60);
        applyConfig(outputName, sourceName, pattern);
    }

    public void applyConfig(String outputName, String sourceName, int pattern) {
        this.pattern = Clamps.i(pattern, 0,
                dev.nano.ndidisplays.client.ndi.TestPatternGenerator.PATTERN_COUNT - 1);
        applyConfig(outputName, sourceName);
    }

    public void applyConfig(String outputName, String sourceName) {
        this.outputName = Clamps.name(outputName, MAX_NAME);
        this.sourceName = Clamps.name(sourceName, MAX_NAME);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Output", outputName);
        tag.putString("Source", sourceName);
        tag.putInt("Pattern", pattern);
        tag.putInt("PatternRes", patternResolution);
        tag.putInt("PatternFps", patternFps);
    }

    /** Also handles the client sync packet, so both names are re-clamped rather than trusted. */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        outputName = Clamps.name(tag.getString("Output"), MAX_NAME);
        sourceName = Clamps.name(tag.getString("Source"), MAX_NAME);
        pattern = Clamps.i(tag.getInt("Pattern"), 0,
                dev.nano.ndidisplays.client.ndi.TestPatternGenerator.PATTERN_COUNT - 1);
        patternResolution = Clamps.i(tag.contains("PatternRes")
                ? tag.getInt("PatternRes") : DEFAULT_PATTERN_RESOLUTION,
                0, RES_W.length - 1);
        patternFps = Clamps.i(tag.contains("PatternFps")
                ? tag.getInt("PatternFps") : DEFAULT_PATTERN_FPS, 1, 60);
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
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) {
            load(packet.getTag());
            if (level != null && level.isClientSide) {
                // Repatch immediately rather than waiting for the next poll.
                dev.nano.ndidisplays.client.ndi.RouterManager.markDirty(worldPosition);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            dev.nano.ndidisplays.client.ndi.RouterManager.register(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            dev.nano.ndidisplays.client.ndi.RouterManager.unregister(this);
        }
        super.setRemoved();
    }
}
