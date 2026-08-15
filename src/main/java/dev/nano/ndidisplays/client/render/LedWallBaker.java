package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.LedPanelBlockEntity;
import dev.nano.ndidisplays.block.WallScanner;
import dev.nano.ndidisplays.client.ClientSetup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Pre-bakes the LED wall simulation into a texture when a shader pack is active.
 *
 * The wall's core shader cannot run inside a pack's deferred pipeline, but nothing
 * stops it running *outside* it: at the start of the render tick — before the pack's
 * world pass begins — each visible wall's simulated LED output (subpixel stripes,
 * bezel gaps, gamma, calibration variance) is rendered into an off-screen texture
 * with the normal wall shader. The wall renderer then draws that texture through a
 * vanilla emissive RenderType the pack knows how to patch. Mipmapping the baked
 * texture reproduces the distance fade the live shader would compute.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, value = Dist.CLIENT)
public final class LedWallBaker {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Texels rendered per LED cell — 6 resolves the R|G|B stripes; capped by texture size. */
    private static final int MAX_TEXELS_PER_LED = 6;
    private static final int MAX_TEXTURE_SIZE = 4096;
    private static final long IDLE_EVICT_MS = 5_000;

    private static final class Baked {
        int texId;
        int fbo;
        int width;
        int height;
        ResourceLocation location;
        long lastUsed;
    }

    private static final Map<BlockPos, Baked> BAKED = new HashMap<>();
    private static final Map<BlockPos, LedPanelBlockEntity> QUEUE = new HashMap<>();
    private static int nextBakeIndex;

    private LedWallBaker() {
    }

    /**
     * Called from the wall renderer while a shader pack is active. Queues the wall for
     * (re)baking next frame and returns the current baked texture, or null until the
     * first bake has happened.
     */
    public static ResourceLocation request(LedPanelBlockEntity be) {
        QUEUE.put(be.getBlockPos(), be);
        Baked baked = BAKED.get(be.getBlockPos());
        if (baked != null) {
            baked.lastUsed = System.currentTimeMillis();
            return baked.location;
        }
        return null;
    }

    /** Bakes queued walls before the world render pass, where no pack pipeline is active. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        evictIdle();
        if (QUEUE.isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }

        // The bake draws NDC-space quads: both matrices are identity for its duration.
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        try {
            for (LedPanelBlockEntity be : QUEUE.values()) {
                try {
                    bake(be);
                } catch (Throwable t) {
                    LOGGER.warn("[ndidisplays] wall bake failed at {}: {}", be.getBlockPos(), t.toString());
                }
            }
        } finally {
            QUEUE.clear();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    private static void bake(LedPanelBlockEntity be) {
        WallScanner.WallInfo wall = be.getWallInfo();
        ShaderInstance shader = be.isBlowThrough()
                ? ClientSetup.ledWallTransparentShader : ClientSetup.ledWallShader;
        if (wall == null || shader == null || be.isRemoved()) {
            return;
        }

        int mode = be.getTestPattern();
        int sourceTex;
        if (mode == 0) {
            sourceTex = ScreenVideo.textureId(be.getSourceName());
        } else {
            sourceTex = FallbackTextures.white();
        }

        int pxPerBlock = be.getPixelsPerBlock();
        float gridW = pxPerBlock * wall.width();
        float gridH = pxPerBlock * wall.height();
        int perLed = Math.max(1, Math.min(MAX_TEXELS_PER_LED,
                (int) Math.floor(MAX_TEXTURE_SIZE / Math.max(gridW, gridH))));
        int width = Math.min(MAX_TEXTURE_SIZE, Math.max(16, (int) gridW * perLed));
        int height = Math.min(MAX_TEXTURE_SIZE, Math.max(16, (int) gridH * perLed));

        Baked baked = BAKED.computeIfAbsent(be.getBlockPos(), pos -> new Baked());
        baked.lastUsed = System.currentTimeMillis();
        if (baked.texId == 0 || baked.width != width || baked.height != height) {
            allocate(baked, width, height);
        }

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, baked.fbo);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager._clear(GL11C.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);

        shader.safeGetUniform("LedParams").set(gridW, gridH, 0.15F, be.getEffectiveBrightness());
        shader.safeGetUniform("LedParams2").set(be.getGamma(), (float) mode, (float) pxPerBlock, 0.06F);
        dev.nano.ndidisplays.block.CropWindow crop = be.crop();
        shader.safeGetUniform("UvRegion").set(crop.u0(), crop.v0(), crop.du(), crop.dv());
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, sourceTex);

        // Same UV convention as the wall quad: v=1 along the bottom edge. The baked
        // texture therefore holds the image bottom-up, and the renderer samples it with
        // v=0 at the wall's bottom.
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.vertex(-1.0F, -1.0F, 0.0F).uv(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        builder.vertex(1.0F, -1.0F, 0.0F).uv(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        builder.vertex(1.0F, 1.0F, 0.0F).uv(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        builder.vertex(-1.0F, 1.0F, 0.0F).uv(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(builder.end());

        // Mipmaps supply the distance fade the live shader would otherwise compute.
        GlStateManager._bindTexture(baked.texId);
        GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
        GlStateManager._bindTexture(0);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    private static void allocate(Baked baked, int width, int height) {
        if (baked.texId != 0) {
            GlStateManager._deleteTexture(baked.texId);
        }
        if (baked.fbo != 0) {
            GlStateManager._glDeleteFramebuffers(baked.fbo);
        }
        baked.texId = GlStateManager._genTexture();
        GlStateManager._bindTexture(baked.texId);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height,
                0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL30C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL30C.GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(0);
        baked.fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, baked.fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, baked.texId, 0);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        baked.width = width;
        baked.height = height;
        if (baked.location == null) {
            baked.location = new ResourceLocation(NdiDisplays.MODID, "wall_bake_" + (nextBakeIndex++));
            Minecraft.getInstance().getTextureManager()
                    .register(baked.location, new ExternalGlTexture(baked.texId));
        } else {
            // Re-register so the TextureManager points at the new GL id.
            Minecraft.getInstance().getTextureManager()
                    .register(baked.location, new ExternalGlTexture(baked.texId));
        }
    }

    private static void evictIdle() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Baked>> it = BAKED.entrySet().iterator();
        while (it.hasNext()) {
            Baked baked = it.next().getValue();
            if (now - baked.lastUsed > IDLE_EVICT_MS) {
                if (baked.location != null) {
                    Minecraft.getInstance().getTextureManager().release(baked.location);
                } else if (baked.texId != 0) {
                    GlStateManager._deleteTexture(baked.texId);
                }
                if (baked.fbo != 0) {
                    GlStateManager._glDeleteFramebuffers(baked.fbo);
                }
                it.remove();
            }
        }
    }
}
