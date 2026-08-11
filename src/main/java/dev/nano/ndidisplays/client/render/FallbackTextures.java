package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** 1x1 solid textures used when no NDI frame is available or a test pattern is active. */
public final class FallbackTextures {

    private static int black;
    private static int white;
    private static ResourceLocation whiteLocation;

    private FallbackTextures() {
    }

    /**
     * The white texture registered with the TextureManager, so it can be used with
     * vanilla RenderTypes (e.g. for the Shimmer bloom pass of test patterns).
     */
    public static ResourceLocation whiteLocation() {
        if (whiteLocation == null) {
            whiteLocation = new ResourceLocation(NdiDisplays.MODID, "solid_white");
            Minecraft.getInstance().getTextureManager().register(whiteLocation, new ExternalGlTexture(white()));
        }
        return whiteLocation;
    }

    public static int black() {
        if (black == 0) {
            black = makeSolid(0, 0, 0);
        }
        return black;
    }

    public static int white() {
        if (white == 0) {
            white = makeSolid(255, 255, 255);
        }
        return white;
    }

    private static int makeSolid(int r, int g, int b) {
        RenderSystem.assertOnRenderThread();
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        ByteBuffer buf = MemoryUtil.memAlloc(4);
        buf.put((byte) r).put((byte) g).put((byte) b).put((byte) 255).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
        MemoryUtil.memFree(buf);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        return id;
    }
}
