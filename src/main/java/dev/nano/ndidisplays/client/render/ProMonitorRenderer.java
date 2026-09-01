package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.nano.ndidisplays.block.ProMonitorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import org.joml.Matrix4f;

/**
 * Puts the live feed on the production monitor's panel. The chassis is the block model; this
 * pass draws exactly one quad, on the mesh's own screen bounds, through the same rotation.
 */
public class ProMonitorRenderer implements BlockEntityRenderer<ProMonitorBlockEntity> {

    private static final float SCR_X0 = 0.0569F;
    private static final float SCR_X1 = 0.9431F;
    private static final float SCR_Y0 = 0.1716F;
    private static final float SCR_Y1 = 0.6787F;
    private static final float SCR_Z = 0.5897F + 0.004F;

    @Override
    public void render(ProMonitorBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        String source = be.getSourceName();
        if (source.isBlank()) {
            return; // the mesh's own dark glass reads as a monitor that is off
        }
        int tex = ScreenVideo.textureId(source);
        if (tex == 0) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(be.getFacing().toYRot() + 180.0F)));
        pose.translate(-0.5, 0.0, -0.5);

        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        float b = be.getBrightness();
        RenderSystem.setShaderColor(b, b, b, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        Matrix4f mat = pose.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(mat, SCR_X0, SCR_Y0, SCR_Z).uv(0.0F, 1.0F).endVertex();
        builder.vertex(mat, SCR_X1, SCR_Y0, SCR_Z).uv(1.0F, 1.0F).endVertex();
        builder.vertex(mat, SCR_X1, SCR_Y1, SCR_Z).uv(1.0F, 0.0F).endVertex();
        builder.vertex(mat, SCR_X0, SCR_Y1, SCR_Z).uv(0.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        pose.popPose();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
