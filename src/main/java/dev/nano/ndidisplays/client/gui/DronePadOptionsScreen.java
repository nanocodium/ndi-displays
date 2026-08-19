package dev.nano.ndidisplays.client.gui;

import dev.nano.ndidisplays.ClientConfig;
import dev.nano.ndidisplays.client.DroneGamepad;
import dev.nano.ndidisplays.client.PadBinding;
import dev.nano.ndidisplays.client.StickBinding;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;

/** Bind each drone action to a controller button or trigger. */
public class DronePadOptionsScreen extends Screen {

    private final Screen parent;
    @Nullable
    private ForgeConfigSpec.ConfigValue<String> listening;
    private int listenTicks;
    private int stickSummaryY;

    public DronePadOptionsScreen(Screen parent) {
        super(Component.translatable("gui.ndidisplays.pad.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 160;
        int y = 32;

        y = bindRow(left, y, "gui.ndidisplays.pad.climb", ClientConfig.DRONE_PAD_CLIMB);
        y = bindRow(left, y, "gui.ndidisplays.pad.descend", ClientConfig.DRONE_PAD_DESCEND);
        y = bindRow(left, y, "gui.ndidisplays.pad.exit", ClientConfig.DRONE_PAD_EXIT);
        y = bindRow(left, y, "gui.ndidisplays.pad.waypoint", ClientConfig.DRONE_PAD_WAYPOINT);
        y = bindRow(left, y, "gui.ndidisplays.pad.menu", ClientConfig.DRONE_PAD_MENU);
        y = bindRow(left, y, "gui.ndidisplays.pad.path_play", ClientConfig.DRONE_PAD_PATH_PLAY);
        y = bindRow(left, y, "gui.ndidisplays.pad.path_stop", ClientConfig.DRONE_PAD_PATH_STOP);
        y += 6;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.pad.calibrate"),
                b -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new DroneStickCalibrateScreen(this));
                    }
                })
                .bounds(left, y, 320, 20).build());
        y += 24;
        stickSummaryY = y;
        y += 14;

        addRenderableWidget(CycleButton.onOffBuilder(ClientConfig.DRONE_PAD_INVERT_LOOK_Y.get())
                .create(left, y, 320, 20, Component.translatable("gui.ndidisplays.pad.invert_look"),
                        (b, val) -> {
                            ClientConfig.DRONE_PAD_INVERT_LOOK_Y.set(val);
                            ClientConfig.SPEC.save();
                        }));
        y += 28;

        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.pad.reset"),
                b -> resetDefaults())
                .bounds(left, y, 156, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 164, y, 156, 20).build());
    }

    private int bindRow(int left, int y, String key, ForgeConfigSpec.ConfigValue<String> value) {
        addRenderableWidget(Button.builder(Component.translatable(key), b -> {})
                .bounds(left, y, 156, 20).build()).active = false;
        addRenderableWidget(Button.builder(bindLabel(value), b -> startListen(value))
                .bounds(left + 164, y, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ndidisplays.pad.clear"),
                b -> save(value, "unbound"))
                .bounds(left + 288, y, 32, 20).build());
        return y + 22;
    }

    private Component bindLabel(ForgeConfigSpec.ConfigValue<String> value) {
        if (listening == value) {
            return Component.translatable("gui.ndidisplays.pad.listening");
        }
        return PadBinding.parse(value.get()).label();
    }

    private void startListen(ForgeConfigSpec.ConfigValue<String> value) {
        listening = value;
        listenTicks = 100;
        rebuildWidgets();
    }

    private void save(ForgeConfigSpec.ConfigValue<String> value, String raw) {
        value.set(raw);
        ClientConfig.SPEC.save();
        listening = null;
        rebuildWidgets();
    }

    private void resetDefaults() {
        save(ClientConfig.DRONE_PAD_CLIMB, "button:0+axis:5");
        save(ClientConfig.DRONE_PAD_DESCEND, "axis:4");
        save(ClientConfig.DRONE_PAD_EXIT, "button:1+button:6");
        save(ClientConfig.DRONE_PAD_WAYPOINT, "button:3");
        save(ClientConfig.DRONE_PAD_MENU, "button:7");
        save(ClientConfig.DRONE_PAD_PATH_PLAY, "unbound");
        save(ClientConfig.DRONE_PAD_PATH_STOP, "unbound");
        save(ClientConfig.DRONE_PAD_MOVE_STICK, "left");
        save(ClientConfig.DRONE_PAD_LOOK_STICK, "right");
        ClientConfig.DRONE_PAD_GUID.set("");
        ClientConfig.DRONE_PAD_INVERT_LOOK_Y.set(false);
        ClientConfig.SPEC.save();
    }

    @Override
    public void tick() {
        super.tick();
        if (listening == null) {
            return;
        }
        if (--listenTicks <= 0) {
            listening = null;
            rebuildWidgets();
            return;
        }
        PadBinding.Part part = DroneGamepad.capture();
        if (part != null) {
            save(listening, part.serialize());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null && keyCode == 256) {
            listening = null;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        StickBinding move = StickBinding.parse(ClientConfig.DRONE_PAD_MOVE_STICK.get());
        StickBinding look = StickBinding.parse(ClientConfig.DRONE_PAD_LOOK_STICK.get());
        graphics.drawCenteredString(font, Component.translatable("gui.ndidisplays.pad.stick_summary",
                        move.label(), look.label()),
                width / 2, stickSummaryY, 0xA0A0A0);
        Component hint = DroneGamepad.anyPadPresent()
                ? Component.translatable("gui.ndidisplays.pad.hint")
                : Component.translatable("gui.ndidisplays.pad.no_pad");
        graphics.drawCenteredString(font, hint, width / 2, height - 18,
                DroneGamepad.anyPadPresent() ? 0xA0A0A0 : 0xFF5555);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
