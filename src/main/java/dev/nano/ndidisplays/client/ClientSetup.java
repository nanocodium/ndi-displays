package dev.nano.ndidisplays.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.client.render.CameraRenderer;
import dev.nano.ndidisplays.client.render.LedWallRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    /** The LED wall core shader; reloaded with resource packs via RegisterShadersEvent. */
    public static ShaderInstance ledWallShader;

    /**
     * MRT variant of the wall shader for Shimmer's post-entity bloom pass: output 0
     * is the visible wall (identical pixels), output 1 feeds Shimmer's bloom buffer.
     */
    public static ShaderInstance ledWallBloomShader;

    /** Blow-through variant: alpha-blended, with the inter-emitter gaps discarded. */
    public static ShaderInstance ledWallTransparentShader;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(NdiDisplays.LED_PANEL_BE.get(), ctx -> new LedWallRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.CAMERA_BE.get(), ctx -> new CameraRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.KINETIC_WINCH_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.KineticPanelRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.ROUND_SCREEN_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.RoundScreenRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.CURVED_SCREEN_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.CurvedScreenRenderer());
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(NdiDisplays.MODID, "led_wall"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> ledWallShader = shader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(NdiDisplays.MODID, "led_wall_bloom"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> ledWallBloomShader = shader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(NdiDisplays.MODID, "led_wall_transparent"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> ledWallTransparentShader = shader);
    }
}
