package dev.nano.ndidisplays.client.render;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Adapter exposing a GL texture we manage ourselves (the NDI stream texture)
 * through the TextureManager, so vanilla RenderTypes can bind it by
 * ResourceLocation (needed for Shimmer's batched bloom pass).
 */
public class ExternalGlTexture extends AbstractTexture {

    public ExternalGlTexture(int glId) {
        this.id = glId;
    }

    @Override
    public void load(ResourceManager resourceManager) {
    }

    @Override
    public int getId() {
        return id;
    }
}
