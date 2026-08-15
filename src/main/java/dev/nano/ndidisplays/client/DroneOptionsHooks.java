package dev.nano.ndidisplays.client;

import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.gui.DronePadOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Adds a Drone controller button to vanilla Options / Controls, and the mod config. */
public final class DroneOptionsHooks {

    private DroneOptionsHooks() {
    }

    @Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT)
    public static final class ModBus {

        private ModBus() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (mc, parent) -> new DronePadOptionsScreen(parent)));
        }
    }

    @Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static final class ForgeBus {

        private ForgeBus() {
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Screen screen = event.getScreen();
            if (!(screen instanceof OptionsScreen) && !(screen instanceof KeyBindsScreen)) {
                return;
            }
            int x = screen instanceof OptionsScreen ? 5 : screen.width / 2 + 5;
            int y = screen instanceof OptionsScreen ? 5 : 8;
            event.addListener(Button.builder(Component.translatable("gui.ndidisplays.pad.open"),
                    b -> Minecraft.getInstance().setScreen(new DronePadOptionsScreen(screen)))
                    .bounds(x, y, 150, 20).build());
        }
    }
}
