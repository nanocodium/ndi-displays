package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.activecam.ActiveCamPose;
import dev.nano.ndidisplays.net.ActiveCamSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client snapshot buffer: Hermite pose for render + NDI. */
public final class ActiveCamClientState {

    private static final class Buffer {
        ActiveCamSnapshotPacket a;
        ActiveCamSnapshotPacket b;
    }

    private static final Map<BlockPos, Buffer> STATES = new ConcurrentHashMap<>();

    private ActiveCamClientState() {
    }

    public static void apply(ActiveCamSnapshotPacket packet) {
        if (packet == null || packet.controller() == null) {
            return;
        }
        Buffer buf = STATES.computeIfAbsent(packet.controller(), p -> new Buffer());
        buf.a = buf.b;
        buf.b = packet;
    }

    public static void remove(BlockPos pos) {
        if (pos != null) {
            STATES.remove(pos);
        }
    }

    @Nullable
    public static ActiveCamPose interpolated(BlockPos controller, float partialTick) {
        if (controller == null) {
            return null;
        }
        Buffer buf = STATES.get(controller);
        if (buf == null || buf.b == null) {
            return null;
        }
        if (buf.a == null || ActiveCamPilotMode.active()) {
            return buf.b.pose().copy();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return buf.b.pose().copy();
        }
        double now = mc.level.getGameTime() + partialTick;
        double t0 = buf.a.gameTime();
        double t1 = buf.b.gameTime();
        double dtTicks = Math.max(1.0, t1 - t0);
        float t = (float) ((now - t0) / dtTicks);
        t = Mth.clamp(t, 0.0F, 1.35F);
        return ActiveCamPose.hermite(buf.a.pose(), buf.b.pose(), t, dtTicks / 20.0);
    }

    @Nullable
    public static ActiveCamSnapshotPacket latest(BlockPos controller) {
        if (controller == null) {
            return null;
        }
        Buffer buf = STATES.get(controller);
        return buf == null ? null : buf.b;
    }
}
