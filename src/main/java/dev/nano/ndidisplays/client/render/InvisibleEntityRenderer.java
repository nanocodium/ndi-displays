package dev.nano.ndidisplays.client.render;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Draws nothing. Minecraft requires every entity type to have a renderer, but the jib seat is
 * only a position for the rider to occupy — the jib's own model already shows the arm.
 */
public class InvisibleEntityRenderer extends EntityRenderer<Entity> {

    public InvisibleEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(Entity entity) {
        return new ResourceLocation("minecraft", "textures/misc/white.png");
    }
}
