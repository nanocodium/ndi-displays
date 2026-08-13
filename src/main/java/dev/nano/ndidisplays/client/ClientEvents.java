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
            // Receivers are re-acquired lazily by the wall renderers, so a full reset is safe.
            NdiManager.shutdownAll();
            // Senders and router registrations must NOT be blanket-wiped here: on a fresh
            // join, the first chunks — and the onLoad() registrations of the cameras and
            // routers in them — can arrive in the very same tick that swaps the level, so
            // a full shutdown threw those registrations away and every rig near the player
            // stayed dark until its block was broken and re-placed. Purging only entries
            // whose block entity belongs to another level keeps same-tick registrations
            // and still drops everything from the dimension that was just left.
            CameraFeedManager.purgeStale(level);
            dev.nano.ndidisplays.client.ndi.RouterManager.purgeStale(level);
        }
        NdiManager.tick();
        dev.nano.ndidisplays.client.ndi.RouterManager.tick();
        if (dev.nano.ndidisplays.client.render.LedWallRenderer.SHIMMER_LOADED) {
            dev.nano.ndidisplays.client.render.ShimmerSphereLights.tick();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastLevel = null;
        NdiManager.shutdownAll();
        CameraFeedManager.shutdownAll();
        dev.nano.ndidisplays.client.ndi.RouterManager.shutdownAll();
        if (dev.nano.ndidisplays.client.render.LedWallRenderer.SHIMMER_LOADED) {
            dev.nano.ndidisplays.client.render.ShimmerSphereLights.clearAll();
        }
    }
}
