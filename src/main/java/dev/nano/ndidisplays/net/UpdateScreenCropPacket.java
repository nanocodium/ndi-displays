package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.CurvedScreenBlockEntity;
import dev.nano.ndidisplays.block.FloorScanner;
import dev.nano.ndidisplays.block.LedFloorBlockEntity;
import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.RoundScreenBlockEntity;
import dev.nano.ndidisplays.block.WallScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: sets a screen's input window (video-processor crop) — the
 * rectangle of the source frame the screen displays. Works on LED walls (fans out
 * to every panel of the detected wall so the wall stays one coherent surface),
 * round screens and curved screens.
 */
public record UpdateScreenCropPacket(BlockPos pos, float u0, float v0, float u1, float v1) {

    public static void encode(UpdateScreenCropPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeFloat(msg.u0);
        buf.writeFloat(msg.v0);
        buf.writeFloat(msg.u1);
        buf.writeFloat(msg.v1);
    }

    public static UpdateScreenCropPacket decode(FriendlyByteBuf buf) {
        return new UpdateScreenCropPacket(buf.readBlockPos(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(UpdateScreenCropPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            Level level = player.level();
            if (!level.isLoaded(msg.pos)
                    || msg.pos.distToCenterSqr(player.getX(), player.getY(), player.getZ()) > 64 * 64) {
                return;
            }
            BlockEntity be = level.getBlockEntity(msg.pos);
            if (be instanceof LedPanelBlockEntity panel) {
                applyToWall(level, panel, msg);
            } else if (be instanceof RoundScreenBlockEntity round) {
                round.crop().set(msg.u0, msg.v0, msg.u1, msg.v1);
                sync(level, round);
            } else if (be instanceof CurvedScreenBlockEntity curved) {
                curved.crop().set(msg.u0, msg.v0, msg.u1, msg.v1);
                sync(level, curved);
            } else if (be instanceof LedFloorBlockEntity floor) {
                applyToFloor(level, floor, msg);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** Every panel of the wall gets the same window: the anchor draws for all of them. */
    private static void applyToWall(Level level, LedPanelBlockEntity panel, UpdateScreenCropPacket msg) {
        WallScanner.WallInfo wall = panel.getWallInfo();
        if (wall == null) {
            panel.crop().set(msg.u0, msg.v0, msg.u1, msg.v1);
            sync(level, panel);
            return;
        }
        Vec3i right = wall.facing().rightStep();
        for (int w = 0; w < wall.width(); w++) {
            for (int h = 0; h < wall.height(); h++) {
                BlockPos p = wall.anchor()
                        .offset(right.getX() * w, right.getY() * w, right.getZ() * w)
                        .above(h);
                if (level.getBlockEntity(p) instanceof LedPanelBlockEntity other) {
                    other.crop().set(msg.u0, msg.v0, msg.u1, msg.v1);
                    sync(level, other);
                }
            }
        }
    }

    /** Every tile of the floor gets the same window. */
    private static void applyToFloor(Level level, LedFloorBlockEntity tile, UpdateScreenCropPacket msg) {
        FloorScanner.FloorInfo floor = tile.getFloorInfo();
        if (floor == null) {
            tile.crop().set(msg.u0, msg.v0, msg.u1, msg.v1);
            sync(level, tile);
            return;
        }
        net.minecraft.core.Direction right = FloorScanner.right(floor.facing());
        for (int w = 0; w < floor.width(); w++) {
            for (int d = 0; d < floor.depth(); d++) {
                BlockPos p = floor.anchor().relative(right, w).relative(floor.facing(), d);
                if (level.getBlockEntity(p) instanceof LedFloorBlockEntity other) {
                    other.crop().set(msg.u0, msg.v0, msg.u1, msg.v1);
                    sync(level, other);
                }
            }
        }
    }

    private static void sync(Level level, BlockEntity be) {
        be.setChanged();
        BlockState state = be.getBlockState();
        level.sendBlockUpdated(be.getBlockPos(), state, state, 3);
    }
}
