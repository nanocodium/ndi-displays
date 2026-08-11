package dev.nano.ndidisplays.client.render;

import java.lang.reflect.Method;

/**
 * Detects whether a shader pack is currently rendering the world.
 *
 * The LED wall's own core shader cannot run inside a shader pack's deferred
 * pipeline — the pack replaces the world's shader programs and expects geometry
 * to write its G-buffers, so a plain mod core shader renders as nothing (a black
 * or invisible screen). When a pack is active the wall drops to a vanilla
 * RenderType the pack knows how to patch instead.
 *
 * Reflection only — Iris/Oculus and OptiFine are soft dependencies.
 */
public final class ShaderPackCompat {

    private static boolean probed;
    private static Object irisApi;
    private static Method irisInUse;
    private static Method optifineIsShaders;

    private ShaderPackCompat() {
    }

    public static boolean shaderPackActive() {
        if (!probed) {
            probed = true;
            probe();
        }
        try {
            if (irisInUse != null) {
                return (Boolean) irisInUse.invoke(irisApi);
            }
            if (optifineIsShaders != null) {
                return (Boolean) optifineIsShaders.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void probe() {
        try {
            // Iris, and Oculus (its Forge port) — both expose the same v0 API class.
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = api.getMethod("getInstance").invoke(null);
            irisInUse = api.getMethod("isShaderPackInUse");
            return;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> config = Class.forName("net.optifine.Config");
            optifineIsShaders = config.getMethod("isShaders");
        } catch (Throwable ignored) {
        }
    }
}
