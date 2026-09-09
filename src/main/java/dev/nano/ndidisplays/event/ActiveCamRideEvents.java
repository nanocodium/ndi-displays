package dev.nano.ndidisplays.event;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ActiveCamControllerBlockEntity;
import dev.nano.ndidisplays.entity.ActiveCamGondolaEntity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Only block the accidental sneak-dismount for a short moment after mounting. */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ActiveCamRideEvents {

    private ActiveCamRideEvents() {
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isDismounting()) {
            return;
        }
        if (!(event.getEntityBeingMounted() instanceof ActiveCamGondolaEntity gondola)) {
            return;
        }
        if (event.getLevel().isClientSide) {
            if (dev.nano.ndidisplays.client.ActiveCamPilotMode.shouldKeepMounted()) {
                event.setCanceled(true);
            }
            return;
        }
        ActiveCamControllerBlockEntity ctrl = gondola.controller();
        if (ctrl != null && ctrl.isDismountBlocked()) {
            event.setCanceled(true);
        }
    }
}
