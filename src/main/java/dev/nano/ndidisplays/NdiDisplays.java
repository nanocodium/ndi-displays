package dev.nano.ndidisplays;

import dev.nano.ndidisplays.block.CameraKind;
import dev.nano.ndidisplays.block.CameraTrackBlock;
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
                        output.accept(BROADCAST_CAMERA_ITEM.get());
                        output.accept(PTZ_CAMERA_ITEM.get());
                        output.accept(JIB_CAMERA_ITEM.get());
                        output.accept(TRACK_CAMERA_ITEM.get());
                        output.accept(CAMERA_TRACK_ITEM.get());
                        output.accept(HANDHELD_CAMERA_ITEM.get());
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
