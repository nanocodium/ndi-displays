package dev.nano.ndidisplays;

import dev.nano.ndidisplays.block.CameraKind;
import dev.nano.ndidisplays.block.CameraTrackBlock;
import dev.nano.ndidisplays.block.KineticWinchBlock;
import dev.nano.ndidisplays.block.KineticWinchBlockEntity;
import dev.nano.ndidisplays.block.LedCornerBlock;
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
    public static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<Block> LED_PANEL = BLOCKS.register("led_panel",
            () -> new LedPanelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

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
                    .lightLevel(state -> 0)
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false), true));

    public static final RegistryObject<Item> BLOW_THROUGH_PANEL_ITEM = ITEMS.register("blow_through_panel",
            () -> new BlockItem(BLOW_THROUGH_PANEL.get(), new Item.Properties()));

    /**
     * 90° corner cabinet: a one-block quarter-cylinder that joins two cardinal LED runs into
     * a single path wall. Sneak-place for the inner (concave) form.
     */
    public static final RegistryObject<Block> LED_CORNER = BLOCKS.register("led_corner",
            () -> new LedCornerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> LED_CORNER_ITEM = ITEMS.register("led_corner",
            () -> new BlockItem(LED_CORNER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<LedPanelBlockEntity>> LED_PANEL_BE = BLOCK_ENTITIES.register("led_panel",
            () -> BlockEntityType.Builder.of(LedPanelBlockEntity::new,
                    LED_PANEL.get(), BLOW_THROUGH_PANEL.get(), LED_CORNER.get()).build(null));

    /**
     * Walkable LED floor tile. Adjacent same-facing tiles merge into one video
     * rectangle, like the walls, but drawn on the XZ plane so you can walk on it.
     */
    public static final RegistryObject<Block> LED_FLOOR = BLOCKS.register("led_floor",
            () -> new dev.nano.ndidisplays.block.LedFloorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> LED_FLOOR_ITEM = ITEMS.register("led_floor",
            () -> new BlockItem(LED_FLOOR.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.LedFloorBlockEntity>> LED_FLOOR_BE =
            BLOCK_ENTITIES.register("led_floor",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.LedFloorBlockEntity::new,
                            LED_FLOOR.get()).build(null));

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
     * Chain hoist: the rigging motor. Unlike the kinetic winch, which flies a rendered
     * LED tile, this one picks up real blocks — a truss grid, a PA hang, a scenery
     * piece — and flies the lot.
     */
    public static final RegistryObject<Block> CHAIN_HOIST = BLOCKS.register("chain_hoist",
            () -> new dev.nano.ndidisplays.block.ChainHoistBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> CHAIN_HOIST_ITEM = ITEMS.register("chain_hoist",
            () -> new BlockItem(CHAIN_HOIST.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<
            dev.nano.ndidisplays.block.ChainHoistBlockEntity>> CHAIN_HOIST_BE =
            BLOCK_ENTITIES.register("chain_hoist",
                    () -> BlockEntityType.Builder.of(
                            dev.nano.ndidisplays.block.ChainHoistBlockEntity::new,
                            CHAIN_HOIST.get()).build(null));

    /**
     * Circular LED screen: one mount block rendering a video disc of configurable
     * radius — the round-screen counterpart of the rectangular walls.
     */
    /**
     * Video projector: throws its content onto whatever world geometry its frustum hits,
     * with occlusion — projection mapping onto buildings and scenery instead of a screen.
     */
    public static final RegistryObject<Block> PROJECTOR = BLOCKS.register("projector",
            () -> new dev.nano.ndidisplays.block.ProjectorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> PROJECTOR_ITEM = ITEMS.register("projector",
            () -> new BlockItem(PROJECTOR.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.ProjectorBlockEntity>> PROJECTOR_BE =
            BLOCK_ENTITIES.register("projector",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.ProjectorBlockEntity::new,
                            PROJECTOR.get()).build(null));

    public static final RegistryObject<Block> ROUND_SCREEN = BLOCKS.register("round_screen",
            () -> new dev.nano.ndidisplays.block.RoundScreenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

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
                    .lightLevel(state -> 0)));

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
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> MULTIVIEW_ITEM = ITEMS.register("multiview",
            () -> new BlockItem(MULTIVIEW.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.MultiviewBlockEntity>> MULTIVIEW_BE =
            BLOCK_ENTITIES.register("multiview",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.MultiviewBlockEntity::new,
                            MULTIVIEW.get()).build(null));

    /**
     * Control-room monitor bound to a kinetic winch park via the NDI card selection.
     */
    public static final RegistryObject<Block> WINCH_PARK_MONITOR = BLOCKS.register("winch_park_monitor",
            () -> new dev.nano.ndidisplays.block.WinchParkMonitorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> WINCH_PARK_MONITOR_ITEM = ITEMS.register("winch_park_monitor",
            () -> new BlockItem(WINCH_PARK_MONITOR.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.WinchParkMonitorBlockEntity>> WINCH_PARK_MONITOR_BE =
            BLOCK_ENTITIES.register("winch_park_monitor",
                    () -> BlockEntityType.Builder.of(dev.nano.ndidisplays.block.WinchParkMonitorBlockEntity::new,
                            WINCH_PARK_MONITOR.get()).build(null));

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
     * Radio remote for chain hoists: emergency stop, group selector, up / stop / down.
     * Right-click a motor to patch it into the remote's selected group.
     */
    public static final RegistryObject<Item> HOIST_REMOTE_ITEM = ITEMS.register("hoist_remote",
            () -> new dev.nano.ndidisplays.item.HoistRemoteItem(new Item.Properties().stacksTo(1)));

    /** Placeable NDI drone: right-click the ground, then fly it from a linked remote. */
    public static final RegistryObject<Item> DRONE_ITEM = ITEMS.register("drone",
            () -> new dev.nano.ndidisplays.item.DroneItem(new Item.Properties().stacksTo(1)));

    /** Links to one drone and enters FPV, or opens the path / NDI GUI while sneaking. */
    public static final RegistryObject<Item> DRONE_REMOTE_ITEM = ITEMS.register("drone_remote",
            () -> new dev.nano.ndidisplays.item.DroneRemoteItem(new Item.Properties().stacksTo(1)));

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

    /**
     * Web terminal: a workstation that renders a page and publishes it as an NDI source, so a
     * browser becomes just another source any screen in the world can select.
     */
    /**
     * A personal computer: a native desktop OS rendered by the mod itself (no browser engine),
     * usable with mouse and keyboard, shown on its in-world monitor, and broadcast as the NDI
     * source "MC Computer <name>".
     */
    public static final RegistryObject<Block> COMPUTER = BLOCKS.register("computer",
            () -> new dev.nano.ndidisplays.block.ComputerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.4F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> COMPUTER_ITEM = ITEMS.register("computer",
            () -> new BlockItem(COMPUTER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.ComputerBlockEntity>> COMPUTER_BE =
            BLOCK_ENTITIES.register("computer",
                    () -> BlockEntityType.Builder.of(
                            dev.nano.ndidisplays.block.ComputerBlockEntity::new,
                            COMPUTER.get()).build(null));

    /** ATEM-style vision switcher: eight NDI inputs, PGM/PVW buses, real transitions. */
    public static final RegistryObject<Block> VISION_SWITCHER = BLOCKS.register("vision_switcher",
            () -> new dev.nano.ndidisplays.block.SwitcherBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.2F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> VISION_SWITCHER_ITEM = ITEMS.register("vision_switcher",
            () -> new BlockItem(VISION_SWITCHER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.SwitcherBlockEntity>> SWITCHER_BE =
            BLOCK_ENTITIES.register("vision_switcher",
                    () -> BlockEntityType.Builder.of(
                            dev.nano.ndidisplays.block.SwitcherBlockEntity::new,
                            VISION_SWITCHER.get()).build(null));

    /** Production monitor: one NDI source on a desk display. */
    public static final RegistryObject<Block> PRO_MONITOR = BLOCKS.register("pro_monitor",
            () -> new dev.nano.ndidisplays.block.ProMonitorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.2F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> PRO_MONITOR_ITEM = ITEMS.register("pro_monitor",
            () -> new BlockItem(PRO_MONITOR.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.ProMonitorBlockEntity>> PRO_MONITOR_BE =
            BLOCK_ENTITIES.register("pro_monitor",
                    () -> BlockEntityType.Builder.of(
                            dev.nano.ndidisplays.block.ProMonitorBlockEntity::new,
                            PRO_MONITOR.get()).build(null));

    /** 19-inch equipment rack: six slots for functional rack units, powered by a PDU. */
    public static final RegistryObject<Block> EQUIPMENT_RACK = BLOCKS.register("equipment_rack",
            () -> new dev.nano.ndidisplays.block.RackBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.6F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Item> EQUIPMENT_RACK_ITEM = ITEMS.register("equipment_rack",
            () -> new BlockItem(EQUIPMENT_RACK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<dev.nano.ndidisplays.block.RackBlockEntity>> RACK_BE =
            BLOCK_ENTITIES.register("equipment_rack",
                    () -> BlockEntityType.Builder.of(
                            dev.nano.ndidisplays.block.RackBlockEntity::new,
                            EQUIPMENT_RACK.get()).build(null));

    private static final java.util.Map<dev.nano.ndidisplays.block.RackUnitType, RegistryObject<Item>>
            RACK_UNIT_ITEMS = new java.util.EnumMap<>(dev.nano.ndidisplays.block.RackUnitType.class);

    static {
        for (dev.nano.ndidisplays.block.RackUnitType type
                : dev.nano.ndidisplays.block.RackUnitType.values()) {
            RACK_UNIT_ITEMS.put(type, ITEMS.register(type.itemName,
                    () -> new dev.nano.ndidisplays.item.RackUnitItem(type, new Item.Properties())));
        }
    }

    public static Item rackUnitItem(dev.nano.ndidisplays.block.RackUnitType type) {
        return RACK_UNIT_ITEMS.get(type).get();
    }

    public static final RegistryObject<Block> WEB_TERMINAL = BLOCKS.register("web_terminal",
            () -> new dev.nano.ndidisplays.block.WebTerminalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.4F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 0)));

    public static final RegistryObject<Item> WEB_TERMINAL_ITEM = ITEMS.register("web_terminal",
            () -> new BlockItem(WEB_TERMINAL.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<
            dev.nano.ndidisplays.block.WebTerminalBlockEntity>> WEB_TERMINAL_BE =
            BLOCK_ENTITIES.register("web_terminal",
                    () -> BlockEntityType.Builder.of(
                            dev.nano.ndidisplays.block.WebTerminalBlockEntity::new,
                            WEB_TERMINAL.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MODID))
                    .icon(() -> new ItemStack(LED_PANEL_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(LED_PANEL_ITEM.get());
                        output.accept(LED_CORNER_ITEM.get());
                        output.accept(BLOW_THROUGH_PANEL_ITEM.get());
                        output.accept(LED_FLOOR_ITEM.get());
                        output.accept(KINETIC_WINCH_ITEM.get());
                        output.accept(CHAIN_HOIST_ITEM.get());
                        output.accept(ROUND_SCREEN_ITEM.get());
                        output.accept(PROJECTOR_ITEM.get());
                        output.accept(CURVED_SCREEN_ITEM.get());
                        output.accept(MULTIVIEW_ITEM.get());
                        output.accept(WINCH_PARK_MONITOR_ITEM.get());
                        output.accept(BROADCAST_CAMERA_ITEM.get());
                        output.accept(PTZ_CAMERA_ITEM.get());
                        output.accept(JIB_CAMERA_ITEM.get());
                        output.accept(TRACK_CAMERA_ITEM.get());
                        output.accept(CAMERA_TRACK_ITEM.get());
                        output.accept(HANDHELD_CAMERA_ITEM.get());
                        output.accept(SHOULDER_CAMERA_ITEM.get());
                        output.accept(NDI_CONFIG_CARD_ITEM.get());
                        output.accept(HOIST_REMOTE_ITEM.get());
                        output.accept(NDI_ROUTER_ITEM.get());
                        output.accept(WEB_TERMINAL_ITEM.get());
                        output.accept(COMPUTER_ITEM.get());
                        output.accept(VISION_SWITCHER_ITEM.get());
                        output.accept(PRO_MONITOR_ITEM.get());
                        output.accept(EQUIPMENT_RACK_ITEM.get());
                        for (dev.nano.ndidisplays.block.RackUnitType t
                                : dev.nano.ndidisplays.block.RackUnitType.values()) {
                            output.accept(rackUnitItem(t));
                        }
                        output.accept(DRONE_ITEM.get());
                        output.accept(DRONE_REMOTE_ITEM.get());
                    })
                    .build());

    /**
     * The jib operator's seat. Tiny, invisible and never saved — it exists only while somebody
     * is riding, because riding is the only way Minecraft lets a block carry a player.
     */
    public static final RegistryObject<net.minecraft.world.entity.EntityType<
            dev.nano.ndidisplays.entity.JibSeatEntity>> JIB_SEAT =
            ENTITIES.register("jib_seat", () -> net.minecraft.world.entity.EntityType.Builder
                    .<dev.nano.ndidisplays.entity.JibSeatEntity>of(
                            dev.nano.ndidisplays.entity.JibSeatEntity::new,
                            net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.4F, 0.4F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .noSummon()
                    .build("jib_seat"));

    /**
     * Persistent quadcopter. Saved with the world, ridden for FPV, and published as
     * an NDI source from the same gimbal pose the pilot sees.
     */
    public static final RegistryObject<net.minecraft.world.entity.EntityType<
            dev.nano.ndidisplays.entity.DroneEntity>> DRONE =
            ENTITIES.register("drone", () -> net.minecraft.world.entity.EntityType.Builder
                    .<dev.nano.ndidisplays.entity.DroneEntity>of(
                            dev.nano.ndidisplays.entity.DroneEntity::new,
                            net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.75F, 0.28F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("drone"));

    /**
     * A structure in flight. It carries the only copy of the load's blocks and NBT while
     * a chain hoist moves it, so it is saved with the world and never summonable — the
     * hoist creates it, the hoist lands it.
     */
    public static final RegistryObject<net.minecraft.world.entity.EntityType<
            dev.nano.ndidisplays.entity.MovingRigEntity>> MOVING_RIG =
            ENTITIES.register("moving_rig", () -> net.minecraft.world.entity.EntityType.Builder
                    .<dev.nano.ndidisplays.entity.MovingRigEntity>of(
                            dev.nano.ndidisplays.entity.MovingRigEntity::new,
                            net.minecraft.world.entity.MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .fireImmune()
                    .noSummon()
                    .build("moving_rig"));

    public NdiDisplays() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);
        ENTITIES.register(modBus);
        NetworkHandler.init();
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        // Rigging limits are the server's call: a flown load has to obey the same caps for
        // everyone on it.
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                dev.nano.ndidisplays.hoist.HoistConfig.SPEC);
    }
}
