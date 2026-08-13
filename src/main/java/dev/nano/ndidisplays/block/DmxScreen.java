package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.compat.theatrical.TheatricalCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * A fixed screen the lighting console can take over: 2 DMX channels — dimmer and
 * source select over eight preconfigured NDI slots. Implemented by the LED wall
 * panel and the round/curved screens; the shared state lives in {@link ScreenDmxState}.
 */
public interface DmxScreen {

    ScreenDmxState dmx();

    BlockPos getBlockPos();

    @Nullable
    Level getLevel();

    /** RDM model name shown in Theatrical's patch screens. */
    String getDmxModelName();

    /** Translation key of the owning block, for Theatrical's consumer lists. */
    String getDmxTranslationKey();

    /**
     * One DMX frame, server side: CH1 dimmer (0-255), CH2 raw source-select byte.
     * Implementations update their video state and sync to clients when it changed.
     */
    void applyDmxFrame(int dimmer, int sourceByte);

    // ------------------------------------------------------------- config card

    /** Theatrical's configuration card, matched by registry name (soft dependency). */
    ResourceLocation THEATRICAL_CONFIG_CARD = new ResourceLocation("theatrical", "configuration_card");

    static boolean isTheatricalCard(ItemStack stack) {
        return THEATRICAL_CONFIG_CARD.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    /**
     * Applies Theatrical's configuration card to a screen: patch network, universe and
     * address exactly like a fixture, auto-increment stepping the screen's 2-channel
     * footprint. Mirrors the winch's card handling.
     */
    static void applyTheatricalCard(Level level, BlockPos pos, BlockState state,
                                    Player player, ItemStack card, DmxScreen screen) {
        CompoundTag tag = card.getOrCreateTag();
        TheatricalCompat.unregisterScreen(screen);
        screen.dmx().applyPatch(
                tag.hasUUID("network") ? tag.getUUID("network") : null,
                tag.getBoolean("universeEnabled") ? tag.getInt("dmxUniverse") : null,
                tag.getBoolean("addressEnabled") ? tag.getInt("dmxAddress") : null);
        TheatricalCompat.registerScreen(screen);
        if (tag.getBoolean("autoIncrement")) {
            tag.putInt("dmxAddress", tag.getInt("dmxAddress") + ScreenDmxState.CHANNEL_COUNT);
        }
        if (screen instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            be.setChanged();
        }
        level.sendBlockUpdated(pos, state, state, 3);
        player.displayClientMessage(Component.translatable(
                "gui.ndidisplays.screen.dmx_patched",
                screen.dmx().getUniverse(), screen.dmx().getAddress()), true);
    }
}
