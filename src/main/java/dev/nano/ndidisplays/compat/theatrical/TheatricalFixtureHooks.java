package dev.nano.ndidisplays.compat.theatrical;

import dev.imabad.theatrical.api.Fixture;
import dev.imabad.theatrical.api.FixtureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * Client-side Theatrical access for the flown fixture payload. Only classloaded when
 * Theatrical is present (see {@link TheatricalCompat}).
 *
 * Theatrical's renderers are anchored on a placed block entity, which a flown
 * fixture doesn't have — so this resolves the {@link Fixture} definition through a
 * throwaway block entity created from the block's default state (every Theatrical
 * light BE returns a static fixture definition, no level access involved), and hands
 * the model locations/pivots back as vanilla-typed {@link FixtureModelData}.
 */
final class TheatricalFixtureHooks {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private TheatricalFixtureHooks() {
    }

    /**
     * The DMX modes the fixture at {@code blockId} really declares, in its own order and
     * with its own names, or an empty list when it declares none.
     *
     * Slots are identified by their RDM slot id first and their label second. The id is the
     * reliable signal — Theatrical's shared slots map colour onto the subtractive CMY ids,
     * so red arrives as {@code SD_COLOR_SUB_CYAN} — while the label catches fixtures that
     * roll their own slots but name them conventionally.
     */
    static java.util.List<FixturePersonality> personalities(String blockId) {
        Fixture fixture = fixture(blockId);
        if (fixture == null || fixture.getDMXPersonalities() == null) {
            LOGGER.info("[ndidisplays] '{}' declares no DMX modes ({}); the winch will offer"
                    + " its generic footprints", blockId,
                    fixture == null ? "not a Theatrical fixture" : "null personality list");
            return java.util.List.of();
        }
        java.util.List<FixturePersonality> out = new java.util.ArrayList<>();
        for (dev.imabad.theatrical.api.dmx.DMXPersonality p : fixture.getDMXPersonalities()) {
            java.util.List<dev.imabad.theatrical.api.dmx.DMXSlot> slots = p.getSlots();
            // Trust the declared channel count, not the slot list length: a fixture may
            // declare more channels than it bothers to describe.
            int count = Math.max(p.getChannelCount(), 0);
            int[] kinds = new int[count];
            for (int i = 0; i < count; i++) {
                kinds[i] = i < slots.size() ? kindOf(slots.get(i)) : FixturePersonality.SLOT_UNKNOWN;
            }
            String desc = p.getDescription() == null || p.getDescription().isBlank()
                    ? count + "ch"
                    : p.getDescription();
            out.add(new FixturePersonality(desc, count, kinds));
        }
        // Cached per block id upstream, so this logs once per fixture type — enough to see in
        // the log whether the real modes were found and what each channel was taken to mean.
        LOGGER.info("[ndidisplays] '{}' DMX modes: {}", blockId, describe(out));
        return out;
    }

    private static String describe(java.util.List<FixturePersonality> list) {
        StringBuilder sb = new StringBuilder();
        for (FixturePersonality p : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(p.description()).append("\" (").append(p.channelCount())
                    .append("ch -> ").append(KineticWinchLead() + p.channelCount())
                    .append(" total) slots=");
            for (int k : p.slots()) {
                sb.append(switch (k) {
                    case FixturePersonality.SLOT_INTENSITY -> "I";
                    case FixturePersonality.SLOT_RED -> "R";
                    case FixturePersonality.SLOT_GREEN -> "G";
                    case FixturePersonality.SLOT_BLUE -> "B";
                    case FixturePersonality.SLOT_FOCUS -> "F";
                    case FixturePersonality.SLOT_PAN -> "P";
                    case FixturePersonality.SLOT_TILT -> "T";
                    default -> "?";
                });
            }
        }
        return sb.toString();
    }

    private static int KineticWinchLead() {
        return dev.nano.ndidisplays.block.KineticWinchBlockEntity.WINCH_LEAD_CHANNELS;
    }

    private static int kindOf(dev.imabad.theatrical.api.dmx.DMXSlot slot) {
        if (slot == null) {
            return FixturePersonality.SLOT_UNKNOWN;
        }
        if (slot.slotID() != null) {
            switch (slot.slotID()) {
                case SD_INTENSITY -> {
                    return FixturePersonality.SLOT_INTENSITY;
                }
                case SD_COLOR_SUB_CYAN -> {
                    return FixturePersonality.SLOT_RED;
                }
                case SD_COLOR_SUB_MAGENTA -> {
                    return FixturePersonality.SLOT_GREEN;
                }
                case SD_COLOR_SUB_YELLOW -> {
                    return FixturePersonality.SLOT_BLUE;
                }
                case SD_BEAM_SIZE_IRIS -> {
                    return FixturePersonality.SLOT_FOCUS;
                }
                case SD_PAN -> {
                    return FixturePersonality.SLOT_PAN;
                }
                case SD_TILT -> {
                    return FixturePersonality.SLOT_TILT;
                }
                default -> {
                    // fall through to the label check
                }
            }
        }
        String label = slot.label() == null ? "" : slot.label().toLowerCase(java.util.Locale.ROOT);
        return switch (label) {
            case "intensity", "dimmer" -> FixturePersonality.SLOT_INTENSITY;
            case "red" -> FixturePersonality.SLOT_RED;
            case "green" -> FixturePersonality.SLOT_GREEN;
            case "blue" -> FixturePersonality.SLOT_BLUE;
            case "focus", "zoom" -> FixturePersonality.SLOT_FOCUS;
            case "pan" -> FixturePersonality.SLOT_PAN;
            case "tilt" -> FixturePersonality.SLOT_TILT;
            default -> FixturePersonality.SLOT_UNKNOWN;
        };
    }

    /** The fixture definition behind a block id, or null when it is not a Theatrical light. */
    @Nullable
    private static Fixture fixture(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return null;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (!(block instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
        return be instanceof FixtureProvider provider ? provider.getFixture() : null;
    }

    @Nullable
    static FixtureModelData resolve(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return null;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null || !(block instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, state);
        if (!(be instanceof FixtureProvider provider)) {
            return null;
        }
        Fixture fixture = provider.getFixture();
        if (fixture == null) {
            return null;
        }
        return new FixtureModelData(state,
                fixture.getStaticModel(),
                fixture.hasPanModel() ? fixture.getPanModel() : null,
                fixture.hasTiltModel() ? fixture.getTiltModel() : null,
                fixture.getPanRotationPosition(),
                fixture.getTiltRotationPosition(),
                fixture.getBeamStartPosition(),
                fixture.getBeamWidth());
    }
}
