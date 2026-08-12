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
public record ApplyNdiCardRegionPacket(boolean mainHand, String source, boolean clearOnly) {

    /** Selections larger than this per horizontal axis are rejected outright. */
    private static final int MAX_SPAN = 512;

    public static void encode(ApplyNdiCardRegionPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.mainHand);
        buf.writeUtf(msg.source, LedPanelBlockEntity.MAX_SOURCE_NAME);
        buf.writeBoolean(msg.clearOnly);
    }

    public static ApplyNdiCardRegionPacket decode(FriendlyByteBuf buf) {
        return new ApplyNdiCardRegionPacket(buf.readBoolean(),
                buf.readUtf(LedPanelBlockEntity.MAX_SOURCE_NAME), buf.readBoolean());
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
            stack.getOrCreateTag().putString(NdiConfigCardItem.TAG_SOURCE, source);

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
                        } else if (be instanceof LedPanelBlockEntity panel) {
                            panel.applyConfig(source, panel.getPixelsPerBlock(),
                                    panel.getBrightness(), panel.getGamma(), 0);
                        } else {
                            continue;
                        }
                        BlockState state = level.getBlockState(pos);
                        level.sendBlockUpdated(pos, state, state, 3);
                        applied++;
                    }
                }
            }
            player.displayClientMessage(Component.translatable(
                    "item.ndidisplays.ndi_config_card.applied_region", applied, source), false);
        });
        ctx.get().setPacketHandled(true);
    }
}
