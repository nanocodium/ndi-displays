package dev.nano.ndidisplays.compat.theatrical;

import dev.imabad.theatrical.blockentities.light.BaseLightBlockEntity;
import dev.imabad.theatrical.blocks.light.BaseLightBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

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
    static void applyLive(BlockEntity ghost, CompoundTag captured, int[] values) {
        if (!(ghost instanceof BaseLightBlockEntity light)) {
            return;
        }
        CompoundTag tag = merged(captured, values, ghost.getBlockPos(), light.getDistance());
        ghost.load(tag);

        // Beam length. The fixture works this out in its own ticker, which never runs for a
        // ghost, so without this every flown beam would keep the length it had on the truss
        // — punching through the stage floor on the way up, stopping short on the way down.
        if (light.getIntensity() > 0) {
            double distance = safeRayTrace(light);
            if (distance >= 0 && Math.abs(distance - light.getDistance()) > 0.5) {
                ghost.load(merged(captured, values, ghost.getBlockPos(), distance));
            }
        }
    }

    private static double safeRayTrace(BaseLightBlockEntity light) {
        try {
            return light.doRayTrace();
        } catch (RuntimeException | LinkageError e) {
            // A raycast is a nicety. A fixture whose geometry this build does not
            // understand keeps its captured beam length instead of killing the frame.
            return -1;
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
