package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    /**
     * The level the feeds were built against. A dimension change swaps ClientLevel without
     * ever calling setRemoved on the old level's block entities, so the camera registry kept
     * stale entries — leaking their NDI senders and leaving phantom sources broadcasting a
     * world the player has left. Comparing the level identity each tick catches every case
     * (dimension change, respawn, world switch) without relying on a specific event.
     */
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.client.multiplayer.ClientLevel level =
                net.minecraft.client.Minecraft.getInstance().level;
        if (level != lastLevel) {
            lastLevel = level;
            NdiManager.shutdownAll();
            CameraFeedManager.shutdownAll();
            dev.nano.ndidisplays.client.ndi.RouterManager.shutdownAll();
        }
        NdiManager.tick();
        dev.nano.ndidisplays.client.ndi.RouterManager.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastLevel = null;
        NdiManager.shutdownAll();
        CameraFeedManager.shutdownAll();
        dev.nano.ndidisplays.client.ndi.RouterManager.shutdownAll();
    }
}
