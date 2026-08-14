package dev.nano.ndidisplays;

import dev.nano.ndidisplays.block.CameraKind;
import dev.nano.ndidisplays.block.CameraTrackBlock;
import dev.nano.ndidisplays.block.KineticWinchBlock;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.block.LedPanelBlock;
import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.NdiCameraBlock;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.block.NdiRouterBlock;
import dev.nano.ndidisplays.block.NdiRouterBlockEntity;
import dev.nano.ndidisplays.net.NetworkHandler;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(NdiDisplays.MODID)
public class NdiDisplays {
    public static final String MODID = "ndidisplays";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<Block> LED_PANEL = BLOCKS.register("led_panel",
            () -> new LedPanelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 10)));

    public static final RegistryObject<Item> LED_PANEL_ITEM = ITEMS.register("led_panel",
            () -> new BlockItem(LED_PANEL.get(), new Item.Properties()));

    /**
     * See-through "blow-through" cabinet — transparent/mesh LED of the sort flown in front of
     * lighting rigs so the fixtures behind still read through the screen. Dimmer than a solid
     * cabinet (far less emitter area) and it does not block light or sight.
     */
    public static final RegistryObject<Block> BLOW_THROUGH_PANEL = BLOCKS.register("blow_through_panel",
            () -> new LedPanelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.2F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 6)
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false), true));

    public static final RegistryObject<Item> BLOW_THROUGH_PANEL_ITEM = ITEMS.register("blow_through_panel",
            () -> new BlockItem(BLOW_THROUGH_PANEL.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<LedPanelBlockEntity>> LED_PANEL_BE = BLOCK_ENTITIES.register("led_panel",
            () -> BlockEntityType.Builder.of(LedPanelBlockEntity::new,
                    LED_PANEL.get(), BLOW_THROUGH_PANEL.get()).build(null));

    /**
     * Kinetic winch: flies an LED video tile below itself on rendered cables — the
     * "floating sky" element (Tomorrowland Freedom Stage style). Height, speed and
     * dimmer are DMX-controllable through Theatrical when it is installed.
     */
    public static final RegistryObject<Block> KINETIC_WINCH = BLOCKS.register("kinetic_winch",
            () -> new KineticWinchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> KINETIC_WINCH_ITEM = ITEMS.register("kinetic_winch",
            () -> new BlockItem(KINETIC_WINCH.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<KineticWinchBlockEntity>> KINETIC_WINCH_BE =
            BLOCK_ENTITIES.register("kinetic_winch",
                    () -> BlockEntityType.Builder.of(KineticWinchBlockEntity::new, KINETIC_WINCH.get()).build(null));

    /**
     * Circular LED screen: one mount block rendering a video disc of configurable
     * radius — the round-screen counterpart of the rectangular walls.
     */
    public static final RegistryObject<Block> ROUND_SCREEN = BLOCKS.register("round_screen",
            () -> new dev.nano.ndidisplays.block.RoundScreenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 10)));

    public static final RegistryObject<Item> ROUND_SCREEN_ITEM = ITEMS.register("round_screen",
            () -> new BlockItem(ROUND_SCREEN.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.RoundScreenBlockEntity>> ROUND_SCREEN_BE =
            BLOCK_ENTITIES.register("round_screen",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.RoundScreenBlockEntity::new,
                            ROUND_SCREEN.get()).build(null));

    /**
     * Curved LED screen: a cylindrical arc of configurable radius, opening angle and
     * height. 360 degrees closes it into a full video column.
     */
    public static final RegistryObject<Block> CURVED_SCREEN = BLOCKS.register("curved_screen",
            () -> new dev.nano.ndidisplays.block.CurvedScreenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 10)));

    public static final RegistryObject<Item> CURVED_SCREEN_ITEM = ITEMS.register("curved_screen",
            () -> new BlockItem(CURVED_SCREEN.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.CurvedScreenBlockEntity>> CURVED_SCREEN_BE =
            BLOCK_ENTITIES.register("curved_screen",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.CurvedScreenBlockEntity::new,
                            CURVED_SCREEN.get()).build(null));

    /**
     * Multiview control monitor: a wall screen showing a 2x2 or 3x3 mosaic of NDI
     * sources for the video engineer — direct video, no LED simulation.
     */
    public static final RegistryObject<Block> MULTIVIEW = BLOCKS.register("multiview",
            () -> new dev.nano.ndidisplays.block.MultiviewBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 8)));

    public static final RegistryObject<Item> MULTIVIEW_ITEM = ITEMS.register("multiview",
            () -> new BlockItem(MULTIVIEW.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.MultiviewBlockEntity>> MULTIVIEW_BE =
            BLOCK_ENTITIES.register("multiview",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.MultiviewBlockEntity::new,
                            MULTIVIEW.get()).build(null));

    private static BlockBehaviour.Properties cameraProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(1.2F)
                .sound(SoundType.METAL)
                .noOcclusion();
    }

    public static final RegistryObject<Block> BROADCAST_CAMERA = BLOCKS.register("broadcast_camera",
            () -> new NdiCameraBlock(CameraKind.BROADCAST, cameraProps()));
    public static final RegistryObject<Block> PTZ_CAMERA = BLOCKS.register("ptz_camera",
            () -> new NdiCameraBlock(CameraKind.PTZ, cameraProps()));
    public static final RegistryObject<Block> JIB_CAMERA = BLOCKS.register("jib_camera",
            () -> new NdiCameraBlock(CameraKind.JIB, cameraProps()));
    public static final RegistryObject<Block> TRACK_CAMERA = BLOCKS.register("track_camera",
            () -> new NdiCameraBlock(CameraKind.TRACK, cameraProps()));
    public static final RegistryObject<Block> CAMERA_TRACK = BLOCKS.register("camera_track",
            () -> new CameraTrackBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(1.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> BROADCAST_CAMERA_ITEM = ITEMS.register("broadcast_camera",
            () -> new BlockItem(BROADCAST_CAMERA.get(), new Item.Properties()));
    public static final RegistryObject<Item> PTZ_CAMERA_ITEM = ITEMS.register("ptz_camera",
            () -> new BlockItem(PTZ_CAMERA.get(), new Item.Properties()));
    public static final RegistryObject<Item> JIB_CAMERA_ITEM = ITEMS.register("jib_camera",
            () -> new BlockItem(JIB_CAMERA.get(), new Item.Properties()));
    public static final RegistryObject<Item> TRACK_CAMERA_ITEM = ITEMS.register("track_camera",
            () -> new BlockItem(TRACK_CAMERA.get(), new Item.Properties()));
    public static final RegistryObject<Item> CAMERA_TRACK_ITEM = ITEMS.register("camera_track",
            () -> new BlockItem(CAMERA_TRACK.get(), new Item.Properties()));

    /**
     * ENG shoulder camera carried in hand: while held, the client broadcasts the player's
     * own view as the NDI source "MC Handheld &lt;player&gt;". Pure marker item — the
     * behaviour lives in the client's CameraFeedManager.
     */
    public static final RegistryObject<Item> HANDHELD_CAMERA_ITEM = ITEMS.register("handheld_camera",
            () -> new Item(new Item.Properties().stacksTo(1)));

    /**
     * Shoulder-mounted cine rig, worn in the chest slot: broadcasts the operator's view as
     * "MC Shoulder &lt;player&gt;" while worn, leaving both hands free — the difference between
     * this and the handheld. Cosmetic only, no armour protection.
     */
    public static final RegistryObject<Item> SHOULDER_CAMERA_ITEM = ITEMS.register("shoulder_camera",
            () -> new dev.nano.ndidisplays.item.ShoulderCameraItem(
                    new Item.Properties().stacksTo(1)));

    /**
     * NDI configuration card: pick a source on the card (right-click in the air), then
     * right-click screens to switch them to NDI video with that source — Theatrical's
     * configuration-card workflow, applied to video routing.
     */
    public static final RegistryObject<Item> NDI_CONFIG_CARD_ITEM = ITEMS.register("ndi_config_card",
            () -> new dev.nano.ndidisplays.item.NdiConfigCardItem(new Item.Properties().stacksTo(1)));

    /**
     * NDI router: publishes a stable output name and forwards whichever source is patched
     * to it, using NDI's routing API — no decode or re-encode, so it costs nothing.
     */
    public static final RegistryObject<Block> NDI_ROUTER = BLOCKS.register("ndi_router",
            () -> new NdiRouterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.2F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> NDI_ROUTER_ITEM = ITEMS.register("ndi_router",
            () -> new BlockItem(NDI_ROUTER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<NdiRouterBlockEntity>> ROUTER_BE =
            BLOCK_ENTITIES.register("ndi_router",
                    () -> BlockEntityType.Builder.of(NdiRouterBlockEntity::new, NDI_ROUTER.get()).build(null));

    public static final RegistryObject<BlockEntityType<NdiCameraBlockEntity>> CAMERA_BE = BLOCK_ENTITIES.register("ndi_camera",
            () -> BlockEntityType.Builder.of(NdiCameraBlockEntity::new,
                    BROADCAST_CAMERA.get(), PTZ_CAMERA.get(), JIB_CAMERA.get(), TRACK_CAMERA.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MODID))
                    .icon(() -> new ItemStack(LED_PANEL_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(LED_PANEL_ITEM.get());
                        output.accept(BLOW_THROUGH_PANEL_ITEM.get());
                        output.accept(KINETIC_WINCH_ITEM.get());
                        output.accept(ROUND_SCREEN_ITEM.get());
                        output.accept(CURVED_SCREEN_ITEM.get());
                        output.accept(MULTIVIEW_ITEM.get());
                        output.accept(BROADCAST_CAMERA_ITEM.get());
                        output.accept(PTZ_CAMERA_ITEM.get());
                        output.accept(JIB_CAMERA_ITEM.get());
                        output.accept(TRACK_CAMERA_ITEM.get());
                        output.accept(CAMERA_TRACK_ITEM.get());
                        output.accept(HANDHELD_CAMERA_ITEM.get());
                        output.accept(SHOULDER_CAMERA_ITEM.get());
                        output.accept(NDI_CONFIG_CARD_ITEM.get());
                        output.accept(NDI_ROUTER_ITEM.get());
                    })
                    .build());

    public NdiDisplays() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);
        NetworkHandler.init();
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }
}
