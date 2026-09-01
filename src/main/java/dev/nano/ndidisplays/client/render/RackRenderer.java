package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.RackBlockEntity;
import dev.nano.ndidisplays.block.RackUnitType;
import dev.nano.ndidisplays.client.web.WebBrowsers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws whatever is bolted into a rack. The frame is the block model; each seated unit renders
 * its own mesh at its slot, and power decides how everything looks: with a live PDU the LEDs,
 * displays and buttons run full-bright and the web module's screen carries its page; without
 * power the rack is a stack of dark metal, which is exactly what a rack without power is.
 */
public class RackRenderer implements BlockEntityRenderer<RackBlockEntity> {

    private static final Map<RackUnitType, ResourceLocation> ATLASES =
            new EnumMap<>(RackUnitType.class);

    static {
        for (RackUnitType type : RackUnitType.values()) {
            ATLASES.put(type, new ResourceLocation(NdiDisplays.MODID,
                    "textures/entity/" + type.mesh + "_atlas.png"));
        }
    }

    /** The web module's screen window, unit-local (from the mesh's screen_panel group). */
    private static final float WEB_X0 = -0.1385F;
    private static final float WEB_X1 = 0.3340F;
    private static final float WEB_Y0 = 0.0253F;
    private static final float WEB_Y1 = 0.1197F;
    private static final float WEB_Z = 0.3538F + 0.004F;

    @Override
    public void render(RackBlockEntity rack, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        boolean powered = rack.powered();
        dev.nano.ndidisplays.client.ndi.RackRouters.note(rack);

        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(rack.getFacing().toYRot() + 180.0F)));

        for (int slot = 0; slot < RackBlockEntity.SLOTS; slot++) {
            RackUnitType type = rack.unit(slot);
            if (type == null) {
                continue;
            }
            ObjPartMesh mesh = ObjPartMesh.get(type.mesh);
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(ATLASES.get(type)));
            float y = RackBlockEntity.SLOT_Y0 + slot * RackBlockEntity.SLOT_PITCH;
            // A PDU that is itself switched off shows dark even when another PDU powers the rack.
            boolean unitLit = powered
                    && (type != RackUnitType.PDU || rack.cfg(slot).getBoolean("On"));

            pose.pushPose();
            pose.translate(0.0, y, -0.05);
            mesh.render(pose, vc, packedLight, g -> !isGlow(g));
            mesh.render(pose, vc, unitLit ? LightTexture.FULL_BRIGHT : packedLight,
                    RackRenderer::isGlow);
            if (type == RackUnitType.WEB && powered) {
                drawWebScreen(rack, slot, pose);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    /** Parts that light up when the rack has power: LEDs, displays, backlit keys, switches. */
    private static boolean isGlow(String group) {
        String g = group.toLowerCase(Locale.ROOT);
        return g.contains("led") || g.contains("display") || g.contains("button")
                || g.contains("lamp") || g.contains("switch_light") || g.contains("status")
                || g.contains("screen");
    }

    private static void drawWebScreen(RackBlockEntity rack, int slot, PoseStack pose) {
        String url = rack.cfg(slot).getString("Url");
        if (url.isBlank()) {
            return;
        }
        WebBrowsers.Session session = WebBrowsers.session(rack.webKey(slot), url, 640, 360,
                rack.getLevel() == null ? 0L : rack.getLevel().getGameTime());
        int tex = session == null ? 0 : session.textureId();
        if (tex == 0) {
            return;
        }
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        Matrix4f mat = pose.last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // CEF's image is top-down: V 1 at the window's bottom.
        b.vertex(mat, WEB_X0, WEB_Y0, WEB_Z).uv(0.0F, 1.0F).endVertex();
        b.vertex(mat, WEB_X1, WEB_Y0, WEB_Z).uv(1.0F, 1.0F).endVertex();
        b.vertex(mat, WEB_X1, WEB_Y1, WEB_Z).uv(1.0F, 0.0F).endVertex();
        b.vertex(mat, WEB_X0, WEB_Y1, WEB_Z).uv(0.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
        RenderSystem.enableCull();
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
