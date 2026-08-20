package dev.nano.ndidisplays.client.render;

import com.lowdragmc.shimmer.client.postprocessing.PostProcessing;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nano.ndidisplays.client.ClientSetup;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Optional Shimmer integration. Shimmer's post-entity pass draws submitted
 * geometry with two draw buffers bound: attachment 0 is the main framebuffer
 * (the quad becomes the visible surface) and attachment 1 is the bloom source
 * that gets blurred and composited. A shader must therefore explicitly write
 * both outputs — single-output shaders leave the bloom buffer empty, which is
 * why we draw with our own MRT wall shader (led_wall_bloom) instead of a
 * vanilla RenderType. The on-screen pixels are identical to the normal wall
 * pass, and the glow is generated from the wall's actual simulated LED output.
 *
 * This class must only be loaded when the "shimmer" mod is present — gate
 * every call behind {@code LedWallRenderer.SHIMMER_LOADED}.
 */
public final class ShimmerCompat {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static java.lang.reflect.Field loadFailedField;

    private ShimmerCompat() {
    }

    /**
     * Suppresses all Shimmer post passes (block/entity/particle bloom) by
     * temporarily marking their post chains as failed. Used during NDI camera
     * captures: Shimmer's compositors sample the *real* screen framebuffer
     * (bound at post-chain creation), so letting them run inside an off-screen
     * capture pastes the player's last frame — HUD and all — into the feed.
     * Returns the saved flags to pass to {@link #restorePostChains}.
     */
    public static boolean[] suppressPostChains() {
        try {
            if (loadFailedField == null) {
                loadFailedField = PostProcessing.class.getDeclaredField("loadFailed");
                loadFailedField.setAccessible(true);
            }
            java.util.Collection<PostProcessing> all = PostProcessing.values();
            boolean[] saved = new boolean[all.size()];
            int i = 0;
            for (PostProcessing post : all) {
                saved[i] = loadFailedField.getBoolean(post);
                loadFailedField.setBoolean(post, true);
                i++;
            }
            return saved;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static void restorePostChains(boolean[] saved) {
        if (saved == null || loadFailedField == null) {
            return;
        }
        try {
            int i = 0;
            for (PostProcessing post : PostProcessing.values()) {
                if (i >= saved.length) {
                    break;
                }
                loadFailedField.setBoolean(post, saved[i]);
                i++;
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /**
     * @param ledParams 12 floats: gridW, gridH, gap, brightness, gamma, mode,
     *                  pxPerBlock, variance, cropU0, cropV0, cropDu, cropDv —
     *                  the same values as the direct pass.
     */
    /**
     * Turns Shimmer's bloom off outright for the duration of a capture.
     *
     * Suppressing the post chains stops the capture from PROCESSING bloom, and clearing the queues
     * stops its draw callbacks leaking — but Shimmer's post targets are {@code CopyDepthColorTarget}s
     * that copy from {@code Minecraft.getMainRenderTarget()}, and during a capture that is the
     * camera's target. Stale camera contents left in a post target get composited in the player's
     * frame afterwards: a screen-locked, alpha-blended image of the camera view. This flips
     * Shimmer's own global switch so none of that machinery runs at all while we capture.
     *
     * @return the previous value, for {@link #restoreBloomFilter}, or null if unavailable
     */
    public static Boolean suppressBloomFilter() {
        try {
            java.util.concurrent.atomic.AtomicBoolean flag = PostProcessing.enableBloomFilter;
            if (flag == null) {
                return null;
            }
            boolean was = flag.get();
            flag.set(false);
            return was;
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] cannot suppress Shimmer bloom during captures ({})",
                    t.toString());
            return null;
        }
    }

    /** Restores whatever {@link #suppressBloomFilter} saved. */
    public static void restoreBloomFilter(Boolean was) {
        if (was == null) {
            return;
        }
        try {
            PostProcessing.enableBloomFilter.set(was);
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] could not restore Shimmer bloom flag ({})", t.toString());
        }
    }

    /**
     * Empties Shimmer's deferred bloom queues and hands back their contents.
     *
     * {@code postEntityDrawFilter} and {@code postEntityDrawForce} are lists of draw callbacks,
     * drained when the post chain runs. Anything that queues bloom during an off-screen capture —
     * Shimmer's own bloom blockstates, a fixture's emissive face — bakes the RIG camera's matrices
     * into a closure that is then drawn in the player's frame, which paints the camera's emissive
     * geometry across the sky as a translucent ghost. Suppressing the chain is not enough: that
     * stops the capture from processing bloom, not from queueing it.
     *
     * @return opaque saved state for {@link #restoreBloomQueues}, or null if unavailable
     */
    public static Object isolateBloomQueues() {
        try {
            java.util.Map<PostProcessing, java.util.List<java.util.List<Object>>> saved =
                    new java.util.IdentityHashMap<>();
            for (PostProcessing post : PostProcessing.values()) {
                java.util.List<java.util.List<Object>> pair = new java.util.ArrayList<>(2);
                for (java.lang.reflect.Field field : bloomQueueFields()) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> live = (java.util.List<Object>) field.get(post);
                    pair.add(new java.util.ArrayList<>(live));
                    live.clear();
                }
                saved.put(post, pair);
            }
            return saved;
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] cannot isolate Shimmer's bloom queues across captures ({});"
                    + " camera bloom may ghost onto the screen", t.toString());
            return null;
        }
    }

    /** Discards whatever the capture queued and restores the player's own bloom draws. */
    public static void restoreBloomQueues(Object savedState) {
        if (savedState == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<PostProcessing, java.util.List<java.util.List<Object>>> saved =
                    (java.util.Map<PostProcessing, java.util.List<java.util.List<Object>>>) savedState;
            for (java.util.Map.Entry<PostProcessing, java.util.List<java.util.List<Object>>> e
                    : saved.entrySet()) {
                java.lang.reflect.Field[] fields = bloomQueueFields();
                for (int i = 0; i < fields.length; i++) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> live = (java.util.List<Object>) fields[i].get(e.getKey());
                    live.clear();
                    live.addAll(e.getValue().get(i));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[ndidisplays] could not restore Shimmer's bloom queues ({})", t.toString());
        }
    }

    private static java.lang.reflect.Field[] bloomQueueFields() throws NoSuchFieldException {
        if (bloomQueues == null) {
            java.lang.reflect.Field filter =
                    PostProcessing.class.getDeclaredField("postEntityDrawFilter");
            java.lang.reflect.Field force =
                    PostProcessing.class.getDeclaredField("postEntityDrawForce");
            filter.setAccessible(true);
            force.setAccessible(true);
            bloomQueues = new java.lang.reflect.Field[]{filter, force};
        }
        return bloomQueues;
    }

    private static java.lang.reflect.Field[] bloomQueues;

    public static void submitBloom(Matrix4f pose, Vec3 p00, Vec3 p10, Vec3 p11, Vec3 p01,
                                   ResourceLocation texture, float[] ledParams) {
        if (ClientSetup.ledWallBloomShader == null) {
            return;
        }
        // The consumer is drained later in the same frame's post pass; copy the
        // matrix since the PoseStack entry is reused after the BER returns.
        Matrix4f mat = new Matrix4f(pose);
        RenderType type = BloomRenderType.of(texture, ledParams);
        PostProcessing.BLOOM_UNREAL.postEntity(buffer -> {
            VertexConsumer vc = buffer.getBuffer(type);
            vertex(vc, mat, p00, 0.0F, 1.0F);
            vertex(vc, mat, p10, 1.0F, 1.0F);
            vertex(vc, mat, p11, 1.0F, 0.0F);
            vertex(vc, mat, p01, 0.0F, 0.0F);
        });
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 pos, float u, float v) {
        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .uv(u, v)
                .color(255, 255, 255, 255)
                .endVertex();
    }

    /** RenderType subclass purely for access to the protected state shards. */
    private static final class BloomRenderType extends RenderType {

        private BloomRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
            throw new IllegalStateException("not constructible");
        }

        /**
         * A fresh instance per wall per frame: uniform values are applied at draw
         * time (Shimmer's drain), so each wall needs its own type carrying its
         * captured parameter snapshot. Distinct instances also keep the buffer
         * source from merging walls into one batch under a single uniform set.
         */
        static RenderType of(ResourceLocation texture, float[] p) {
            CompositeState state = CompositeState.builder()
                    .setShaderState(new ShaderStateShard(() -> ClientSetup.ledWallBloomShader))
                    .setTextureState(new TextureStateShard(texture, true, true))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setTexturingState(new TexturingStateShard("ndidisplays_led_params", () -> {
                        ShaderInstance shader = ClientSetup.ledWallBloomShader;
                        if (shader != null) {
                            shader.safeGetUniform("LedParams").set(p[0], p[1], p[2], p[3]);
                            shader.safeGetUniform("LedParams2").set(p[4], p[5], p[6], p[7]);
                            if (p.length >= 12) {
                                shader.safeGetUniform("UvRegion").set(p[8], p[9], p[10], p[11]);
                            } else {
                                shader.safeGetUniform("UvRegion").set(0.0F, 0.0F, 1.0F, 1.0F);
                            }
                        }
                    }, () -> {
                    }))
                    .createCompositeState(false);
            return create("ndidisplays_led_bloom", DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.QUADS, 256, false, false, state);
        }
    }
}
