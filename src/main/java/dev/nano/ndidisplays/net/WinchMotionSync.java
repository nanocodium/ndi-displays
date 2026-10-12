package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side collector that turns per-DMX-frame winch changes into one compact packet per
 * player per tick.
 *
 * DMX arrives faster than the game ticks and touches every winch of a sweep at once. Rather
 * than sync each change the moment it lands, a winch queues itself here; at the end of the
 * level tick every queued winch is written once into a {@link WinchMotionPacket} for each
 * player tracking its chunk. Several frames landing inside one tick collapse into a single
 * update carrying the latest values — the client integrates the same motion profile from
 * the target, so nothing is lost by sending 20 Hz instead of 40.
 *
 * The queue is a concurrent set because the DMX network may deliver frames off the server
 * thread; everything that touches the level happens in the tick handler.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WinchMotionSync {

    /** Winches per packet; keeps a burst over a huge park well under the payload limit. */
    private static final int MAX_PER_PACKET = 1024;

    private static final Map<Level, Set<KineticWinchBlockEntity>> PENDING = new ConcurrentHashMap<>();

    private WinchMotionSync() {
    }

    /** Marks a winch as needing a motion sync on the next tick flush. */
    public static void queue(KineticWinchBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        PENDING.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(be);
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER
                || !(event.level instanceof ServerLevel level)) {
            return;
        }
        Set<KineticWinchBlockEntity> pending = PENDING.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<KineticWinchBlockEntity> batch = new ArrayList<>(pending);
        pending.clear();

        // Group the dirty winches by the players who can see them, one buffer per player.
        Map<ServerPlayer, PlayerBatch> perPlayer = new IdentityHashMap<>();
        Map<ChunkPos, List<ServerPlayer>> watchers = new HashMap<>();
        for (KineticWinchBlockEntity be : batch) {
            if (be.isRemoved() || be.getLevel() != level) {
                continue;
            }
            // Save once per tick instead of once per DMX frame.
            be.setChanged();
            ChunkPos chunk = new ChunkPos(be.getBlockPos());
            List<ServerPlayer> players = watchers.computeIfAbsent(chunk,
                    c -> level.getChunkSource().chunkMap.getPlayers(c, false));
            for (ServerPlayer player : players) {
                perPlayer.computeIfAbsent(player, p -> new PlayerBatch()).add(be);
            }
        }
        for (Map.Entry<ServerPlayer, PlayerBatch> e : perPlayer.entrySet()) {
            e.getValue().send(e.getKey());
        }
    }

    /** Clear everything when a level goes away so no stale block entities are retained. */
    @SubscribeEvent
    public static void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        PENDING.remove(event.getLevel());
    }

    private static final class PlayerBatch {
        private FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        private int count;
        private final List<WinchMotionPacket> ready = new ArrayList<>();

        void add(KineticWinchBlockEntity be) {
            buf.writeBlockPos(be.getBlockPos());
            be.writeMotion(buf);
            if (++count >= MAX_PER_PACKET) {
                seal();
            }
        }

        private void seal() {
            if (count == 0) {
                return;
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            ready.add(new WinchMotionPacket(bytes, count));
            buf = new FriendlyByteBuf(Unpooled.buffer());
            count = 0;
        }

        void send(ServerPlayer player) {
            seal();
            buf.release();
            for (WinchMotionPacket packet : ready) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }
}
