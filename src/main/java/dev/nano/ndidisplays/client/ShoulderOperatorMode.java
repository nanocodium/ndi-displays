package dev.nano.ndidisplays.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.item.ShoulderCameraItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Operator mode for the shoulder rig: the screen shows the lens's own framing, with viewfinder
 * markings over it, while the player still walks and turns normally.
 *
 * It does not take over the camera position — the rig already aims where the operator looks, so
 * the only thing standing between the player's view and the camera's is the lens angle. Matching
 * the field of view is therefore enough to be genuinely looking through it, and it leaves
 * movement completely untouched, which a camera-entity takeover would not.
 */
public final class ShoulderOperatorMode {

    private static boolean active;

    private ShoulderOperatorMode() {
    }

    public static boolean active() {
        return active && wearing();
    }

    private static boolean wearing() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                && mc.player.getItemBySlot(EquipmentSlot.CHEST)
                        .is(NdiDisplays.SHOULDER_CAMERA_ITEM.get());
    }

    /** Key bindings and the toggle, on the mod bus. */
    @Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT)
    public static final class Keys {

        /** Default V for "viewfinder"; the obvious letters are all taken by vanilla. */
        public static final KeyMapping TOGGLE = new KeyMapping(
                "key.ndidisplays.operator_mode",
                InputConstants.Type.KEYSYM, InputConstants.KEY_V,
                "key.categories.ndidisplays");

        private Keys() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE);
        }
    }

    /** Toggle handling and the viewfinder overlay, on the forge bus. */
    @Mod.EventBusSubscriber(modid = NdiDisplays.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static final class Handlers {

        private Handlers() {
        }

        @SubscribeEvent
        public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
                return;
            }
            while (Keys.TOGGLE.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (!wearing()) {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.translatable(
                                "gui.ndidisplays.shoulder.not_worn"), true);
                    }
                    continue;
                }
                active = !active;
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable(active
                            ? "gui.ndidisplays.shoulder.operator_on"
                            : "gui.ndidisplays.shoulder.operator_off"), true);
                }
            }
        }

        /**
         * Draws the camera's own captured frame over the screen, then the viewfinder markings on
         * top — so operator mode really is looking through the lens rather than tinting the
         * player's view. The frame shown is the exact one that went out on NDI.
         *
         * The player's own view still renders underneath and their input is untouched, which is
         * what keeps walking around working while framing a shot.
         */
        @SubscribeEvent
        public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
            if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type() || !active()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            GuiGraphics g = event.getGuiGraphics();
            drawLensView(mc, g);
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();

            // Safe area at 90%, the convention for framing action that must survive a crop.
            int mx = Math.round(w * 0.05F);
            int my = Math.round(h * 0.05F);
            int arm = Math.max(10, Math.min(w, h) / 12);
            int c = 0xB0FFFFFF;
            // Corner brackets rather than a full box: they mark the frame without covering it.
            corner(g, mx, my, arm, arm, c);
            corner(g, w - mx - arm, my, arm, arm, c);
            corner(g, mx, h - my - 1, arm, -arm, c);
            corner(g, w - mx - arm, h - my - 1, arm, -arm, c);

            // Centre cross.
            g.fill(w / 2 - 6, h / 2, w / 2 + 6, h / 2 + 1, c);
            g.fill(w / 2, h / 2 - 6, w / 2 + 1, h / 2 + 6, c);

            ItemStack rig = mc.player == null
                    ? ItemStack.EMPTY
                    : mc.player.getItemBySlot(EquipmentSlot.CHEST);
            String lens = String.format("%.0f°", ShoulderCameraItem.fov(rig));
            g.drawString(mc.font, "● REC", mx + 2, my + 4, 0xFFE04A3A, true);
            g.drawString(mc.font, lens, w - mx - 24, my + 4, 0xFFFFFFFF, true);
            g.drawString(mc.font, Component.translatable("gui.ndidisplays.shoulder.operator_hud")
                    .getString(), mx + 2, h - my - 12, 0xC0C0C0C0, true);
        }

        /**
         * Blits the capture target's colour texture across the screen, letterboxed to preserve
         * the camera's aspect ratio — a stretched viewfinder would misrepresent the framing,
         * which is the one thing it exists to show.
         *
         * The capture is rendered bottom-up (it is a framebuffer, not an image file), so V is
         * flipped here rather than in the capture path, which NDI depends on being upright.
         */
        private static void drawLensView(Minecraft mc, GuiGraphics g) {
            int tex = dev.nano.ndidisplays.client.CameraFeedManager.shoulderCaptureTexture();
            int tw = dev.nano.ndidisplays.client.CameraFeedManager.shoulderCaptureWidth();
            int th = dev.nano.ndidisplays.client.CameraFeedManager.shoulderCaptureHeight();
            if (tex == 0 || tw <= 0 || th <= 0) {
                return;
            }
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            float scale = Math.min(sw / (float) tw, sh / (float) th);
            float dw = tw * scale;
            float dh = th * scale;
            float x0 = (sw - dw) * 0.5F;
            float y0 = (sh - dh) * 0.5F;

            // Letterbox bars, so the area outside the camera's frame reads as not-the-shot.
            if (dh < sh - 0.5F) {
                g.fill(0, 0, sw, (int) Math.ceil(y0), 0xFF000000);
                g.fill(0, (int) (y0 + dh), sw, sh, 0xFF000000);
            }
            if (dw < sw - 0.5F) {
                g.fill(0, 0, (int) Math.ceil(x0), sh, 0xFF000000);
                g.fill((int) (x0 + dw), 0, sw, sh, 0xFF000000);
            }

            com.mojang.blaze3d.systems.RenderSystem.setShader(
                    net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, tex);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            org.joml.Matrix4f mat = g.pose().last().pose();
            com.mojang.blaze3d.vertex.BufferBuilder b =
                    com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
            b.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
            b.vertex(mat, x0, y0 + dh, 0).uv(0.0F, 0.0F).endVertex();
            b.vertex(mat, x0 + dw, y0 + dh, 0).uv(1.0F, 0.0F).endVertex();
            b.vertex(mat, x0 + dw, y0, 0).uv(1.0F, 1.0F).endVertex();
            b.vertex(mat, x0, y0, 0).uv(0.0F, 1.0F).endVertex();
            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(b.end());
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }

        /** One corner bracket; negative lengths draw the mirrored (bottom) version. */
        private static void corner(GuiGraphics g, int x, int y, int lenX, int lenY, int colour) {
            g.fill(x, y, x + lenX, y + 1, colour);
            int y0 = Math.min(y, y + lenY);
            int y1 = Math.max(y, y + lenY);
            g.fill(x, y0, x + 1, y1, colour);
        }
    }
}
