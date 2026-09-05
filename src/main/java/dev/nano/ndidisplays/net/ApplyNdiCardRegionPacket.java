package dev.nano.ndidisplays.net;

import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.item.NdiConfigCardItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Applies the NDI card's source to every screen inside its WorldEdit-style selection
 * box — kinetic winch tiles and LED wall panels alike — in one shot, so a 250-motor
 * array is patched with a single click instead of 250.
 *
 * The box itself is read from the card's NBT on the SERVER's copy of the item, never
 * from the packet: the client only says "apply what my card holds", so a modified
 * client cannot target arbitrary coordinates it never selected.
 */
public record ApplyNdiCardRegionPacket(boolean mainHand, String source, boolean clearOnly,
                                       int winchMode, boolean autoMapCanvas) {

    /** Selections larger than this per horizontal axis are rejected outright. */
    private static final int MAX_SPAN = 512;

    public static void encode(ApplyNdiCardRegionPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.mainHand);
        buf.writeUtf(msg.source, LedPanelBlockEntity.MAX_SOURCE_NAME);
        buf.writeBoolean(msg.clearOnly);
        buf.writeVarInt(msg.winchMode);
        buf.writeBoolean(msg.autoMapCanvas);
    }

    public static ApplyNdiCardRegionPacket decode(FriendlyByteBuf buf) {
        return new ApplyNdiCardRegionPacket(buf.readBoolean(),
                buf.readUtf(LedPanelBlockEntity.MAX_SOURCE_NAME), buf.readBoolean(),
                buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(ApplyNdiCardRegionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            InteractionHand hand = msg.mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof NdiConfigCardItem)) {
                return;
            }
            if (msg.clearOnly) {
                NdiConfigCardItem.clearSelection(stack);
                return;
            }
            if (player.isSpectator() || !player.getAbilities().mayBuild) {
                return;
            }

            String source = msg.source.trim();
            int winchMode = Math.max(NdiConfigCardItem.WINCH_MODE_KEEP,
                    Math.min(NdiConfigCardItem.WINCH_MODE_TWIN, msg.winchMode));
            stack.getOrCreateTag().putString(NdiConfigCardItem.TAG_SOURCE, source);
            stack.getOrCreateTag().putInt(NdiConfigCardItem.TAG_WINCH_MODE, winchMode);
            stack.getOrCreateTag().putBoolean(NdiConfigCardItem.TAG_AUTOMAP, msg.autoMapCanvas);

            BlockPos pos1 = NdiConfigCardItem.selectionPos(stack, NdiConfigCardItem.TAG_POS1);
            BlockPos pos2 = NdiConfigCardItem.selectionPos(stack, NdiConfigCardItem.TAG_POS2);
            ServerLevel level = player.serverLevel();
            if (pos1 == null || pos2 == null || !NdiConfigCardItem.selectionDimension(stack)
                    .equals(level.dimension().location().toString())) {
                return;
            }

            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());
            if (maxX - minX > MAX_SPAN || maxZ - minZ > MAX_SPAN) {
                return;
            }

            // Walk the loaded chunks of the box and pick screens out of their block-entity
            // maps: for a huge but sparse selection this touches a few hundred map entries
            // instead of iterating millions of block positions.
            int applied = 0;
            java.util.List<KineticWinchBlockEntity> park = new java.util.ArrayList<>();
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunk(cx, cz);
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos pos = be.getBlockPos();
                        if (pos.getX() < minX || pos.getX() > maxX
                                || pos.getY() < minY || pos.getY() > maxY
                                || pos.getZ() < minZ || pos.getZ() > maxZ
                                || !level.mayInteract(player, pos)) {
                            continue;
                        }
                        if (be instanceof KineticWinchBlockEntity winch) {
                            winch.applyNdiCard(source);
                            if (winchMode != NdiConfigCardItem.WINCH_MODE_KEEP) {
                                // Re-register: the 4↔6 channel footprint must reach Theatrical.
                                dev.nano.ndidisplays.compat.theatrical.TheatricalCompat.unregister(winch);
                                winch.applyCardWinchMode(winchMode == NdiConfigCardItem.WINCH_MODE_TWIN);
                                dev.nano.ndidisplays.compat.theatrical.TheatricalCompat.register(winch);
                            }
                            park.add(winch);
                        } else if (be instanceof LedPanelBlockEntity panel) {
                            panel.applyConfig(source, panel.getPixelsPerBlock(),
                                    panel.getBrightness(), panel.getGamma(), 0);
                        } else if (be instanceof dev.nano.ndidisplays.block.LedFloorBlockEntity floor) {
                            floor.applyNdiCard(source);
                        } else if (be instanceof dev.nano.ndidisplays.block.RoundScreenBlockEntity round) {
                            round.applyNdiCard(source);
                        } else if (be instanceof dev.nano.ndidisplays.block.CurvedScreenBlockEntity curved) {
                            curved.applyNdiCard(source);
                        } else if (be instanceof dev.nano.ndidisplays.block.ChainHoistBlockEntity hoist) {
                            // A hoist has no video, so the card's name becomes its group:
                            // select the mother grid, click once, and every motor over the
                            // stage answers to one set of buttons.
                            hoist.applyConfig(hoist.getMinChain(), hoist.getMaxChain(),
                                    hoist.getSpeed(), source);
                        } else {
                            continue;
                        }
                        BlockState state = level.getBlockState(pos);
                        level.sendBlockUpdated(pos, state, state, 3);
                        applied++;
                    }
                }
            }
            if (!park.isEmpty()) {
                if (msg.autoMapCanvas) {
                    dev.nano.ndidisplays.winch.WinchParkLayout.applyCanvasMap(park);
                } else {
                    // Stitch off: each motor shows the full source at its own tile,
                    // undoing a previous park-wide canvas map.
                    for (KineticWinchBlockEntity winch : park) {
                        winch.applyCanvasMapping(1, 1, 0, 0);
                    }
                }
                for (KineticWinchBlockEntity winch : park) {
                    BlockState state = level.getBlockState(winch.getBlockPos());
                    level.sendBlockUpdated(winch.getBlockPos(), state, state, 3);
                }
            }
            player.displayClientMessage(Component.translatable(
                    "item.ndidisplays.ndi_config_card.applied_region", applied, source), false);
        });
        ctx.get().setPacketHandled(true);
    }
}
