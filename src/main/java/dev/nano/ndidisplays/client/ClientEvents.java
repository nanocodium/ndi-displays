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

    /**
     * Hides the operator while their own shoulder rig is filming.
     *
     * The lens is mounted on them, so without this the frame is filled by the back of their own
     * head and the camera body — which is what made the rig appear to clip into its own feed.
     * Only the wearer is hidden, and only during that one capture, so everyone else stays in
     * shot and the operator is still visible in every other camera.
     */
    @SubscribeEvent
    public static void onRenderPlayer(
            net.minecraftforge.client.event.RenderPlayerEvent.Pre event) {
        // RenderPlayerEvent, not RenderLivingEvent: Forge fires the player-specific event for
        // players, so a RenderLivingEvent handler never runs for them — which is why the
        // operator's head still filled their own shot.
        if (!dev.nano.ndidisplays.client.CameraFeedManager.isCapturingShoulderRig()) {
            return;
        }
        if (event.getEntity() == net.minecraft.client.Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    /**
     * Operator mode: the lens angle replaces the player's field of view, so the screen frames
     * exactly what the camera is sending. Movement is untouched — this changes what you see,
     * not what you control, so the operator can still walk the shot.
     */
    @SubscribeEvent
    public static void onComputeFov(net.minecraftforge.client.event.ViewportEvent.ComputeFov event) {
        if (!ShoulderOperatorMode.active()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        net.minecraft.world.item.ItemStack rig =
                mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        if (rig.is(NdiDisplays.SHOULDER_CAMERA_ITEM.get())) {
            event.setFOV(dev.nano.ndidisplays.item.ShoulderCameraItem.fov(rig));
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
