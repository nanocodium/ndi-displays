package dev.nano.ndidisplays.hoist;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.ChainHoistBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /hoist} — operating motors without walking to each one.
 *
 * The pendant GUI is how a single hoist is flown, but a show is run in cues, and cues
 * are commands: a whole group to a trim height on one line, from a command block, a
 * function file or a mid-show console. Everything here is available in the GUI too; this
 * is the same set of actions addressed by position or by group name.
 *
 * <pre>
 * /hoist at &lt;pos&gt; up|down|stop|attach|detach|info
 * /hoist at &lt;pos&gt; goto &lt;chain&gt;
 * /hoist group &lt;name&gt; up|down|stop
 * /hoist group &lt;name&gt; goto &lt;chain&gt;
 * </pre>
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HoistCommand {

    private HoistCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // Operator level: flying a truss moves other people's builds around.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hoist")
                .requires(source -> source.hasPermission(2));

        LiteralArgumentBuilder<CommandSourceStack> at = Commands.literal("at")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(action("up", ChainHoistBlockEntity::commandUp))
                        .then(action("down", ChainHoistBlockEntity::commandDown))
                        .then(action("stop", ChainHoistBlockEntity::commandStop))
                        .then(action("attach", ChainHoistBlockEntity::commandAttach))
                        .then(action("detach", ChainHoistBlockEntity::commandDetach))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("chain", FloatArgumentType.floatArg(0))
                                        .executes(ctx -> {
                                            float chain = FloatArgumentType.getFloat(ctx, "chain");
                                            return single(ctx, hoist -> hoist.commandGoto(chain));
                                        })))
                        .then(Commands.literal("info").executes(HoistCommand::info)));

        LiteralArgumentBuilder<CommandSourceStack> group = Commands.literal("group")
                .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.literal("up")
                                .executes(ctx -> groupMove(ctx, true)))
                        .then(Commands.literal("down")
                                .executes(ctx -> groupMove(ctx, false)))
                        .then(groupAction("stop", ChainHoistBlockEntity::commandStop))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("chain", FloatArgumentType.floatArg(0))
                                        .executes(ctx -> {
                                            float chain = FloatArgumentType.getFloat(ctx, "chain");
                                            return group(ctx, hoist -> hoist.commandGoto(chain));
                                        }))));

        event.getDispatcher().register(root.then(at).then(group));
    }

    // ------------------------------------------------------------------ builders

    private static LiteralArgumentBuilder<CommandSourceStack> action(
            String name, Consumer<ChainHoistBlockEntity> action) {
        return Commands.literal(name).executes(ctx -> single(ctx, action));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> groupAction(
            String name, Consumer<ChainHoistBlockEntity> action) {
        return Commands.literal(name).executes(ctx -> group(ctx, action));
    }

    // ------------------------------------------------------------------ execution

    private static int single(CommandContext<CommandSourceStack> ctx,
                              Consumer<ChainHoistBlockEntity> action) throws
            com.mojang.brigadier.exceptions.CommandSyntaxException {
        ChainHoistBlockEntity hoist = hoistAt(ctx);
        action.accept(hoist);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.ndidisplays.hoist.done",
                Component.translatable(hoist.getStatus().translationKey())), true);
        return 1;
    }

    private static int group(CommandContext<CommandSourceStack> ctx,
                             Consumer<ChainHoistBlockEntity> action) {
        return groupAll(ctx, motors -> motors.forEach(action));
    }

    /**
     * Runs the whole group by one shared amount of chain.
     *
     * Each motor has its own trim, so "up" cannot mean "everyone to their own upper limit"
     * — that would flatten a deliberately raked hang on the first cue. It means everyone
     * moves the same distance, governed by whichever motor has the least room left.
     */
    private static int groupMove(CommandContext<CommandSourceStack> ctx, boolean up) {
        return groupAll(ctx, motors -> ChainHoistBlockEntity.groupMove(motors, up));
    }

    private static int groupAll(CommandContext<CommandSourceStack> ctx,
                                Consumer<List<ChainHoistBlockEntity>> action) {
        ServerLevel level = ctx.getSource().getLevel();
        String name = HoistGroups.normalise(StringArgumentType.getString(ctx, "name"));
        List<ChainHoistBlockEntity> motors = new ArrayList<>();
        for (BlockPos pos : HoistGroups.get(level).members(name)) {
            if (level.isLoaded(pos)
                    && level.getBlockEntity(pos) instanceof ChainHoistBlockEntity hoist) {
                motors.add(hoist);
            }
        }
        if (motors.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(
                    "commands.ndidisplays.hoist.empty_group", name));
            return 0;
        }
        action.accept(motors);
        int count = motors.size();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.ndidisplays.hoist.group_done", count, name), true);
        return count;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws
            com.mojang.brigadier.exceptions.CommandSyntaxException {
        ChainHoistBlockEntity hoist = hoistAt(ctx);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.ndidisplays.hoist.info",
                String.format("%.2f", hoist.getChainLength()),
                String.format("%.2f", hoist.getTargetChain()),
                Component.translatable(hoist.getStatus().translationKey()),
                hoist.getLoadBlocks(),
                hoist.getLoadMotors(),
                hoist.getGroup().isEmpty()
                        ? Component.translatable("gui.ndidisplays.hoist.no_group")
                        : Component.literal(hoist.getGroup())), false);
        return 1;
    }

    private static ChainHoistBlockEntity hoistAt(CommandContext<CommandSourceStack> ctx) throws
            com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        if (ctx.getSource().getLevel().getBlockEntity(pos)
                instanceof ChainHoistBlockEntity hoist) {
            return hoist;
        }
        throw NO_HOIST.create();
    }

    private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NO_HOIST =
            new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.translatable("commands.ndidisplays.hoist.no_hoist"));
}
