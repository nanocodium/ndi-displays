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
    public static ShaderInstance ledWallTransparentBloomShader;

    /** Projective texturing for the video projector: frame UVs arrive per vertex. */
    public static ShaderInstance projectorShader;

    /** Layer the worn shoulder rig's geometry is baked into. */
    public static final net.minecraft.client.model.geom.ModelLayerLocation SHOULDER_RIG_LAYER =
            new net.minecraft.client.model.geom.ModelLayerLocation(
                    new ResourceLocation(NdiDisplays.MODID, "shoulder_rig"), "main");

    /** Baked once on first use; the armour layer asks for it every frame it is worn. */
    private static dev.nano.ndidisplays.client.render.ShoulderRigModel shoulderRig;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The seat is a position, not a thing to look at: the jib's own model already shows the
        // arm and the rider is drawn by their own renderer.
        event.registerEntityRenderer(NdiDisplays.JIB_SEAT.get(),
                dev.nano.ndidisplays.client.render.InvisibleEntityRenderer::new);
        event.registerEntityRenderer(NdiDisplays.DRONE.get(),
                dev.nano.ndidisplays.client.render.DroneRenderer::new);
        event.registerEntityRenderer(NdiDisplays.MOVING_RIG.get(),
                dev.nano.ndidisplays.client.render.MovingRigRenderer::new);
    }

    /**
     * The chain and hook are models without blocks, so nothing would bake them: they are
     * drawn by the hoist's renderer, not placed in the world.
     */
    @SubscribeEvent
    public static void onRegisterAdditionalModels(
            net.minecraftforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(dev.nano.ndidisplays.client.render.ChainHoistRenderer.CHAIN_LINK_MODEL);
        event.register(dev.nano.ndidisplays.client.render.ChainHoistRenderer.CHAIN_HOOK_MODEL);
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SHOULDER_RIG_LAYER,
                dev.nano.ndidisplays.client.render.ShoulderRigModel::createLayer);
    }

    public static dev.nano.ndidisplays.client.render.ShoulderRigModel shoulderRig() {
        if (shoulderRig == null) {
            shoulderRig = new dev.nano.ndidisplays.client.render.ShoulderRigModel(
                    net.minecraft.client.Minecraft.getInstance().getEntityModels()
                            .bakeLayer(SHOULDER_RIG_LAYER));
        }
        return shoulderRig;
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(NdiDisplays.LED_PANEL_BE.get(), ctx -> new LedWallRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.LED_FLOOR_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.LedFloorRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.CAMERA_BE.get(), ctx -> new CameraRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.KINETIC_WINCH_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.KineticPanelRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.CHAIN_HOIST_BE.get(),
                dev.nano.ndidisplays.client.render.ChainHoistRenderer::new);
        event.registerBlockEntityRenderer(NdiDisplays.ROUND_SCREEN_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.RoundScreenRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.CURVED_SCREEN_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.CurvedScreenRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.MULTIVIEW_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.MultiviewRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.WINCH_PARK_MONITOR_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.WinchParkMonitorRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.WEB_TERMINAL_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.WebTerminalRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.PROJECTOR_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.ProjectorRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.COMPUTER_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.ComputerRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.PRO_MONITOR_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.ProMonitorRenderer());
        event.registerBlockEntityRenderer(NdiDisplays.RACK_BE.get(),
                ctx -> new dev.nano.ndidisplays.client.render.RackRenderer());
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
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(NdiDisplays.MODID, "led_wall_transparent_bloom"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> ledWallTransparentBloomShader = shader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(NdiDisplays.MODID, "projector"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> projectorShader = shader);
    }
}
