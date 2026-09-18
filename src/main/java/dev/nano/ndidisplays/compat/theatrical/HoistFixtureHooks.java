package dev.nano.ndidisplays.compat.theatrical;

import dev.imabad.theatrical.blockentities.light.BaseDMXConsumerLightBlockEntity;
import dev.imabad.theatrical.blockentities.light.BaseLightBlockEntity;
import dev.imabad.theatrical.blocks.light.BaseLightBlock;
import dev.imabad.theatrical.networks.TheatricalNetworkData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Everything that touches a Theatrical type on behalf of a flown lighting fixture. Only
 * classloaded behind {@link HoistFixtureCompat}'s presence check.
 *
 * <h3>The trick this class rests on</h3>
 * Theatrical registers a light as a DMX consumer from {@code setLevel}, keyed by block
 * position, into a plain map on the network. It never looks the consumer back up through
 * {@code level.getBlockEntity}. So a block entity that exists only in this mod's hands —
 * one whose block is not in the world at all, because a hoist is carrying it — is a
 * perfectly good consumer: it receives the same DMX frames as its neighbours on the truss
 * and keeps its intensity, colour and head position live for the whole flight.
 *
 * The proxy stays registered at the address the fixture took off from, which is also the
 * right answer for a show: flying a truss does not re-patch it.
 */
final class HoistFixtureHooks {

    /** pan, tilt, focus, intensity, red, green, blue. */
    static final int VALUE_COUNT = 7;

    /**
     * Theatrical's interpolation and beam-length state, reached directly. {@code read()}
     * forces prevPan = pan and prevTilt = tilt, so a ghost fed through NBT can never
     * interpolate: every new head position is a snap, and a pan/tilt effect that glides
     * on a bolted fixture stutters on a flown one. Ghosts never tick, so nothing else
     * maintains these fields; this class does it in their place.
     */
    private static final Field PREV_PAN = field("prevPan");
    private static final Field PREV_TILT = field("prevTilt");
    private static final Field DISTANCE = field("distance");

    @Nullable
    private static Field field(String name) {
        try {
            Field f = BaseLightBlockEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private HoistFixtureHooks() {
    }

    static boolean isFixture(BlockState state) {
        return state.getBlock() instanceof BaseLightBlock;
    }

    /**
     * Builds a stand-in for a fixture that has left the world and hands it to Theatrical.
     *
     * @return the proxy, or null when this block turns out not to be a light after all
     */
    @Nullable
    static BlockEntity createProxy(ServerLevel level, BlockPos pos, BlockState state,
                                   @Nullable CompoundTag captured) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (!(be instanceof BaseLightBlockEntity)) {
            return null;
        }
        if (captured != null) {
            be.load(positioned(captured, pos));
        }
        // Registers the consumer. Must come after load(), or it would be patched with a
        // blank universe and channel start and quietly receive nobody's data.
        be.setLevel(level);
        return be;
    }

    static void releaseProxy(BlockEntity proxy) {
        proxy.setRemoved();
    }

    /**
     * Puts a landed fixture back on its Theatrical network.
     *
     * {@code setBlock} creates the block entity and {@code setLevel} registers it while
     * the tag is still empty (null network). {@link BlockEntity#load} then restores the
     * real universe / address / network id, but Theatrical never re-adds the consumer
     * from {@code read}. The operator has to open every fixture and Apply. Calling this
     * after load is the same add Theatrical would have done if the tag had been there
     * first.
     */
    static void reattach(BlockEntity be) {
        if (!(be instanceof BaseDMXConsumerLightBlockEntity light)) {
            return;
        }
        if (light.getLevel() == null || light.getLevel().isClientSide) {
            return;
        }
        var network = TheatricalNetworkData.getInstance(light.getLevel().getServer().overworld())
                .getNetwork(light.getNetworkId());
        if (network != null) {
            network.dmx().addConsumer(light.getBlockPos(), light);
        }
    }

    /** Live head state, or null when this proxy is not a light. */
    @Nullable
    static int[] readLive(BlockEntity proxy) {
        if (!(proxy instanceof BaseLightBlockEntity light)) {
            return null;
        }
        return new int[] {
                light.getPan(),
                light.getTilt(),
                light.getFocus(),
                (int) light.getIntensity(),
                light.getRed(),
                light.getGreen(),
                light.getBlue(),
        };
    }

    /**
     * Pushes live head state onto a client-side ghost fixture.
     *
     * The values go in through the fixture's own NBT rather than setters, because a light
     * only exposes {@code setPan} and {@code setTilt} — intensity and colour are protected.
     * Reloading from a merged tag is both complete and version-proof: whatever a modded
     * fixture keeps alongside the standard fields comes straight back from the snapshot.
     */
    static void applyLive(BlockEntity ghost, CompoundTag captured, int[] values,
                          boolean interpolate) {
        if (!(ghost instanceof BaseLightBlockEntity light)) {
            return;
        }
        int fromPan = light.getPan();
        int fromTilt = light.getTilt();
        ghost.load(merged(captured, values, ghost.getBlockPos(), light.getDistance()));
        if (interpolate) {
            // Sweep from where the head was to where it is now over this tick, exactly as
            // Theatrical's own storePrev() would have arranged on a ticking fixture.
            setInt(PREV_PAN, light, fromPan);
            setInt(PREV_TILT, light, fromTilt);
        }
    }

    /**
     * Ends the previous tick's sweep: previous = current, so the head holds still until
     * the next change. Called once per tick on every ghost that got no new values.
     */
    static void settle(BlockEntity ghost) {
        if (ghost instanceof BaseLightBlockEntity light) {
            setInt(PREV_PAN, light, light.getPan());
            setInt(PREV_TILT, light, light.getTilt());
        }
    }

    /** Beam length without a reload, so a per-frame update cannot disturb the sweep. */
    static void setDistance(BaseLightBlockEntity light, double distance) {
        if (DISTANCE == null) {
            return;
        }
        try {
            DISTANCE.setDouble(light, distance);
        } catch (IllegalAccessException | RuntimeException ignored) {
            // Cosmetic: the beam keeps its last length.
        }
    }

    private static void setInt(@Nullable Field field, BaseLightBlockEntity light, int value) {
        if (field == null) {
            return;
        }
        try {
            field.setInt(light, value);
        } catch (IllegalAccessException | RuntimeException ignored) {
            // Cosmetic: the head snaps instead of sweeping.
        }
    }

    private static CompoundTag merged(CompoundTag captured, int[] values, BlockPos pos,
                                      double distance) {
        CompoundTag tag = positioned(captured, pos);
        tag.putInt("pan", values[0]);
        tag.putInt("tilt", values[1]);
        tag.putInt("focus", values[2]);
        tag.putInt("intensity", values[3]);
        tag.putInt("red", values[4]);
        tag.putInt("green", values[5]);
        tag.putInt("blue", values[6]);
        // Previous values are what the fixture interpolates from. Setting them equal to the
        // new ones stops a ghost rebuilt mid-move from sweeping its head across the stage to
        // catch up with a position it is already in.
        tag.putInt("prevIntensity", values[3]);
        tag.putInt("prevRed", values[4]);
        tag.putInt("prevGreen", values[5]);
        tag.putInt("prevBlue", values[6]);
        tag.putDouble("distance", distance);
        return tag;
    }

    private static CompoundTag positioned(CompoundTag captured, BlockPos pos) {
        CompoundTag tag = captured.copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }
}
