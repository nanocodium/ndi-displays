package dev.nano.ndidisplays.client.render;

import com.lowdragmc.shimmer.client.light.ColorPointLight;
import com.lowdragmc.shimmer.client.light.LightManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Shimmer coloured point lights for kinetic spheres: each sphere carries a dynamic
 * light in its DMX colour that follows the ball up and down the winch and washes the
 * floor underneath — the signature kinetic-lights look.
 *
 * Renderer-driven: {@link KineticPanelRenderer} refreshes the light every frame the
 * sphere is rendered, and {@link #tick()} expires entries that stopped being
 * refreshed (payload changed, block removed, chunk unloaded, dimension left). This
 * class touches Shimmer types, so it must only be classloaded behind
 * {@link LedWallRenderer#SHIMMER_LOADED}.
 */
public final class ShimmerSphereLights {

    /** Ticks without a render refresh before a light is dropped. */
    private static final int EXPIRE_TICKS = 5;

    private static final Map<BlockPos, Holder> LIGHTS = new HashMap<>();
    private static int tickCounter;

    private ShimmerSphereLights() {
    }

    /** Called from the sphere render path each frame; positions are world-space. */
    static void update(BlockPos owner, Vec3 pos, float r, float g, float b) {
        float lum = Math.max(r, Math.max(g, b));
        float radius = 3.0F + 8.0F * Math.min(1.0F, lum);
        Holder holder = LIGHTS.get(owner);
        if (holder == null) {
            ColorPointLight light = LightManager.INSTANCE.addLight(
                    new Vector3f((float) pos.x, (float) pos.y, (float) pos.z),
                    packColor(r, g, b), radius);
            if (light == null) {
                // Shimmer's light budget is full this frame; try again next one.
                return;
            }
            holder = new Holder(light);
            LIGHTS.put(owner, holder);
        }
        holder.lastSeen = tickCounter;
        ColorPointLight light = holder.light;
        light.setPos((float) pos.x, (float) pos.y, (float) pos.z);
        light.radius = radius;
        light.setColor(Math.min(1.0F, r), Math.min(1.0F, g), Math.min(1.0F, b), 1.0F);
        light.setEnable(lum > 0.02F);
        light.update();
    }

    /** Client tick: drop lights whose sphere stopped rendering. */
    public static void tick() {
        tickCounter++;
        Iterator<Map.Entry<BlockPos, Holder>> it = LIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Holder holder = it.next().getValue();
            if (tickCounter - holder.lastSeen > EXPIRE_TICKS) {
                holder.light.remove();
                it.remove();
            }
        }
    }

    /** Logout / world close: release everything. */
    public static void clearAll() {
        for (Holder holder : LIGHTS.values()) {
            holder.light.remove();
        }
        LIGHTS.clear();
    }

    private static int packColor(float r, float g, float b) {
        return 0xFF000000
                | ((int) (Math.min(1.0F, r) * 255) << 16)
                | ((int) (Math.min(1.0F, g) * 255) << 8)
                | (int) (Math.min(1.0F, b) * 255);
    }

    private static final class Holder {
        final ColorPointLight light;
        int lastSeen;

        Holder(ColorPointLight light) {
            this.light = light;
            this.lastSeen = tickCounter;
        }
    }
}
