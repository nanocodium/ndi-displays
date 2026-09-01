package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A vision switcher: eight NDI inputs, a program and a preview bus, and real transitions.
 *
 * The panel works the way an ATEM does. The program bus is what is on air; the preview bus is
 * what goes on air next. CUT swaps them instantly; AUTO runs the selected transition (mix, dip
 * to black, or wipe) over the set rate, after which program and preview have traded places —
 * flip-flop, exactly like the hardware. The switcher's output is its own NDI source
 * ({@code MC Switcher <name>}): the broadcast host composites the transition frame by frame on
 * the GPU and publishes it, so every wall, multiview and OBS sees the cut mid-transition.
 *
 * The server owns all of this state and syncs it, so two operators see the same buses and a
 * transition looks identical to everyone.
 */
public class SwitcherBlockEntity extends BlockEntity {

    public static final int INPUTS = 8;
    public static final int MAX_NAME = 64;

    public static final int[] RES_W = {854, 1280, 1920};
    public static final int[] RES_H = {480, 720, 1080};

    public static final int STYLE_MIX = 0;
    public static final int STYLE_DIP = 1;
    public static final int STYLE_WIPE = 2;

    /** Bus value for black — the input below slot 0. */
    public static final int BLACK = -1;

    private String name = "";
    private final String[] sources = new String[INPUTS];
    private int program = BLACK;
    private int preview = BLACK;
    private int style = STYLE_MIX;
    private int rateTicks = 20;
    /** Game time AUTO began, or -1 when idle. The buses already hold the post-swap values. */
    private long transStart = -1;
    private int transFrom = BLACK;
    private boolean broadcast = true;
    private int resolution = 1;
    private int fps = 30;

    public SwitcherBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.SWITCHER_BE.get(), pos, state);
        java.util.Arrays.fill(sources, "");
    }

    // ------------------------------------------------------------------ reads

    public String getName() {
        return name;
    }

    public String getSource(int slot) {
        return slot >= 0 && slot < INPUTS ? sources[slot] : "";
    }

    /** The live source name for a bus value, or "" for black / unassigned. */
    public String busSource(int bus) {
        return bus == BLACK ? "" : getSource(bus);
    }

    public int getProgram() {
        return program;
    }

    public int getPreview() {
        return preview;
    }

    public int getStyle() {
        return style;
    }

    public int getRateTicks() {
        return rateTicks;
    }

    public long getTransStart() {
        return transStart;
    }

    public int getTransFrom() {
        return transFrom;
    }

    public boolean isBroadcasting() {
        return broadcast;
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

    public Direction getFacing() {
        return getBlockState().getValue(SwitcherBlock.FACING);
    }

    public String getEffectiveSourceName() {
        if (!name.isBlank()) {
            return "MC Switcher " + name;
        }
        return "MC Switcher " + worldPosition.getX() + "," + worldPosition.getY() + ","
                + worldPosition.getZ();
    }

    /** Transition progress 0..1 at the given time, or 1 when idle. */
    public float transitionProgress(long gameTime, float partialTick) {
        if (transStart < 0) {
            return 1.0F;
        }
        float p = (gameTime - transStart + partialTick) / Math.max(1, rateTicks);
        return Math.min(1.0F, Math.max(0.0F, p));
    }

    public boolean transitioning(long gameTime) {
        return transStart >= 0 && gameTime - transStart <= rateTicks + 1;
    }

    // ------------------------------------------------------------------ operations (server)

    public static final int OP_PREVIEW = 0;
    public static final int OP_PROGRAM = 1;
    public static final int OP_CUT = 2;
    public static final int OP_AUTO = 3;
    public static final int OP_STYLE = 4;
    public static final int OP_RATE = 5;
    public static final int OP_SOURCE = 6;
    public static final int OP_NAME = 7;
    public static final int OP_RES = 8;
    public static final int OP_FPS = 9;
    public static final int OP_BROADCAST = 10;

    /** One panel action, validated and applied; the caller syncs the block afterwards. */
    public void handleAction(int op, int index, String text) {
        switch (op) {
            case OP_PREVIEW -> preview = clampBus(index);
            case OP_PROGRAM -> program = clampBus(index);   // hot cut on the program bus
            case OP_CUT -> {
                int p = program;
                program = preview;
                preview = p;
                transStart = -1;
            }
            case OP_AUTO -> {
                if (level != null && transStart < 0 && preview != program) {
                    transFrom = program;
                    program = preview;
                    preview = transFrom;
                    transStart = level.getGameTime();
                }
            }
            case OP_STYLE -> style = Clamps.i(index, 0, 2);
            case OP_RATE -> rateTicks = Clamps.i(index, 5, 100);
            case OP_SOURCE -> {
                if (index >= 0 && index < INPUTS) {
                    sources[index] = Clamps.name(text, 128);
                }
            }
            case OP_NAME -> name = Clamps.name(text, MAX_NAME);
            case OP_RES -> resolution = Clamps.i(index, 0, RES_W.length - 1);
            case OP_FPS -> fps = Clamps.i(index, 1, 60);
            case OP_BROADCAST -> broadcast = index != 0;
            default -> {
            }
        }
        setChanged();
    }

    private static int clampBus(int v) {
        return v < 0 ? BLACK : Math.min(INPUTS - 1, v);
    }

    /** Client ticker: keeps the switcher known to the publisher while its chunk is loaded. */
    public static void clientTick(Level level, BlockPos pos, BlockState state,
                                  SwitcherBlockEntity be) {
        dev.nano.ndidisplays.client.CameraFeedManager.noteSwitcher(be);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Name", name);
        for (int i = 0; i < INPUTS; i++) {
            tag.putString("Src" + i, sources[i]);
        }
        tag.putInt("Program", program);
        tag.putInt("Preview", preview);
        tag.putInt("Style", style);
        tag.putInt("Rate", rateTicks);
        tag.putLong("TransStart", transStart);
        tag.putInt("TransFrom", transFrom);
        tag.putBoolean("Broadcast", broadcast);
        tag.putInt("Res", resolution);
        tag.putInt("Fps", fps);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        name = Clamps.name(tag.getString("Name"), MAX_NAME);
        for (int i = 0; i < INPUTS; i++) {
            sources[i] = Clamps.name(tag.getString("Src" + i), 128);
        }
        program = clampBus(tag.getInt("Program"));
        preview = clampBus(tag.getInt("Preview"));
        style = Clamps.i(tag.getInt("Style"), 0, 2);
        rateTicks = Clamps.i(tag.contains("Rate") ? tag.getInt("Rate") : 20, 5, 100);
        transStart = tag.contains("TransStart") ? tag.getLong("TransStart") : -1;
        transFrom = clampBus(tag.getInt("TransFrom"));
        broadcast = !tag.contains("Broadcast") || tag.getBoolean("Broadcast");
        resolution = Clamps.i(tag.getInt("Res"), 0, RES_W.length - 1);
        fps = Clamps.i(tag.contains("Fps") ? tag.getInt("Fps") : 30, 1, 60);
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
