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
 * A web terminal: a computer that renders a page and puts it on the network as an NDI source.
 *
 * The block itself holds only configuration — the page URL, the output resolution and the name
 * to advertise under. The browser and the NDI sender both live client-side, the same split every
 * other source in this mod uses: the world agrees on <em>what</em> a screen shows, and the
 * broadcast host is the one machine that actually produces pixels.
 *
 * Publishing as NDI rather than as a bespoke texture is what makes it useful — every LED wall,
 * curved and round screen, multiview and kinetic tile already resolves sources by name, as do
 * OBS and vMix, so a page becomes usable everywhere without a single change to those paths.
 */
public class WebTerminalBlockEntity extends BlockEntity {

    /** Matches the wire limit in {@code UpdateWebTerminalPacket} and the GUI edit box. */
    public static final int MAX_URL = 512;
    public static final int MAX_LABEL = 64;

    /** Output resolutions, indexed by {@link #resolution}. */
    public static final int[] RES_W = {854, 1280, 1920};
    public static final int[] RES_H = {480, 720, 1080};

    private static final String DEFAULT_URL = "https://www.google.com/";
    private static final int DEFAULT_RESOLUTION = 1;
    private static final int DEFAULT_FPS = 30;

    private String url = DEFAULT_URL;
    private String label = "";
    private int resolution = DEFAULT_RESOLUTION;
    private int fps = DEFAULT_FPS;
    /** Whether this terminal advertises its page on the network at all. */
    private boolean broadcast = true;

    public WebTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.WEB_TERMINAL_BE.get(), pos, state);
    }

    public String getUrl() {
        return url;
    }

    public String getLabel() {
        return label;
    }

    public int getResolution() {
        return resolution;
    }

    public int getWidth() {
        return RES_W[Math.floorMod(resolution, RES_W.length)];
    }

    public int getHeight() {
        return RES_H[Math.floorMod(resolution, RES_H.length)];
    }

    public int getFps() {
        return fps;
    }

    public boolean isBroadcasting() {
        return broadcast;
    }

    public Direction getFacing() {
        return getBlockState().getValue(WebTerminalBlock.FACING);
    }

    /**
     * The NDI source name. Defaults to the block position so two terminals never collide by
     * accident, and takes the operator's label when they set one — the same rule the camera rigs
     * and routers follow, so every source in a show is named the same way.
     */
    public String getEffectiveSourceName() {
        if (!label.isBlank()) {
            return "MC Web " + label;
        }
        return "MC Web " + worldPosition.getX() + "," + worldPosition.getY() + ","
                + worldPosition.getZ();
    }

    public void applyConfig(String url, String label, int resolution, int fps, boolean broadcast) {
        this.url = Clamps.name(url, MAX_URL);
        this.label = Clamps.name(label, MAX_LABEL);
        this.resolution = Clamps.i(resolution, 0, RES_W.length - 1);
        this.fps = Clamps.i(fps, 1, 60);
        this.broadcast = broadcast;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Url", url);
        tag.putString("Label", label);
        tag.putInt("Res", resolution);
        tag.putInt("Fps", fps);
        tag.putBoolean("Broadcast", broadcast);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        url = Clamps.name(tag.contains("Url") ? tag.getString("Url") : DEFAULT_URL, MAX_URL);
        label = Clamps.name(tag.getString("Label"), MAX_LABEL);
        resolution = Clamps.i(tag.getInt("Res"), 0, RES_W.length - 1);
        fps = Clamps.i(tag.contains("Fps") ? tag.getInt("Fps") : DEFAULT_FPS, 1, 60);
        broadcast = !tag.contains("Broadcast") || tag.getBoolean("Broadcast");
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
    public void setRemoved() {
        // The browser is a Chromium process; it must not outlive the block that owns it.
        if (level != null && level.isClientSide) {
            dev.nano.ndidisplays.client.web.WebBrowsers.close(worldPosition);
        }
        super.setRemoved();
    }
}
