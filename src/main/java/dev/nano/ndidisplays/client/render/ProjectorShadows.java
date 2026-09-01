package dev.nano.ndidisplays.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.nano.ndidisplays.NdiDisplays;
import dev.nano.ndidisplays.block.NdiCameraBlockEntity;
import dev.nano.ndidisplays.block.ProjectorBlockEntity;
import dev.nano.ndidisplays.client.CameraFeedManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-projector shadow maps: the world's depth rendered from the projector's own lens.
 *
 * This is what turns the projector into a physical light. The drape shader compares every
 * fragment's projector-space depth against this map — anything nearer the lens shadows
 * everything behind it, at pixel resolution, entities and players included. Silhouettes scale
 * with distance exactly as in life, because that is just what a perspective depth compare does.
 *
 * The pass reuses the NDI capture engine (the machinery that already renders the world from
 * arbitrary cameras with all its restore/isolation hygiene) pointed at a private depth target.
 * One projector refreshes per frame, round-robin; a lone projector gets per-frame shadows, five
 * share a 5-frame cadence. The shadow camera renders a symmetric padded frustum that covers
 * whatever lens shift and keystone push outside the base cone; the lookup matrix stored here is
 * the one the pass really used, so the compare never drifts from the render.
 */
@Mod.EventBusSubscriber(modid = NdiDisplays.MODID, value = Dist.CLIENT)
public final class ProjectorShadows {

    // 2048² gives near-4K silhouette sharpness for the memory of a single 1080p frame per
    // projector; the PCF radius scales with the texel, so edges tighten automatically.
    public static final int MAP_SIZE = 2048;

    /** Depth compare bias in METRES; adjustable via -Dndidisplays.projectorShadowBias. */
    public static final float BIAS = Float.parseFloat(
            System.getProperty("ndidisplays.projectorShadowBias", "0.06"));

    /** Projectors not rendered for this long lose their map (and its GPU memory). */
    private static final long EXPIRE_MS = 10_000L;

    /** True while the depth pass renders, so drapes and frustum lines stay out of their own map. */
    public static boolean inDepthPass;

    public static final class Shadow {
        public final RenderTarget target = new TextureTarget(MAP_SIZE, MAP_SIZE, true, Minecraft.ON_OSX);
        /** World-space lookup matrix of the most recent pass (projection * view actually used). */
        public Matrix4f matrix = new Matrix4f();
        /** Far plane of the most recent pass, for linearizing depth in the shader. */
        public float farPlane = 256.0F;
        public boolean ready;
        long lastSeen;
        long lastPass;
        ProjectorBlockEntity be;
    }

    private static final Map<BlockPos, Shadow> SHADOWS = new HashMap<>();

    private ProjectorShadows() {
    }

    /** Renderer check-in: this projector is on screen and wants shadows. */
    public static Shadow register(ProjectorBlockEntity be) {
        Shadow s = SHADOWS.computeIfAbsent(be.getBlockPos(), p -> new Shadow());
        s.lastSeen = System.currentTimeMillis();
        s.be = be;
        return s;
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || CameraFeedManager.isCapturing()
                || ShaderPackCompat.shaderPackActive()) {
            return;
        }
        long now = System.currentTimeMillis();

        // Expire maps for projectors no renderer has claimed recently.
        Iterator<Map.Entry<BlockPos, Shadow>> it = SHADOWS.entrySet().iterator();
        while (it.hasNext()) {
            Shadow s = it.next().getValue();
            if (now - s.lastSeen > EXPIRE_MS || s.be == null || s.be.isRemoved()) {
                s.target.destroyBuffers();
                it.remove();
            }
        }

        // One depth pass per frame: the stalest live projector.
        Shadow due = null;
        for (Shadow s : SHADOWS.values()) {
            if (now - s.lastSeen > 250L) {
                continue; // not currently rendered
            }
            if (due == null || s.lastPass < due.lastPass) {
                due = s;
            }
        }
        if (due == null) {
            return;
        }
        due.lastPass = now;
        renderDepth(mc, due);
    }

    private static void renderDepth(Minecraft mc, Shadow shadow) {
        ProjectorBlockEntity be = shadow.be;
        // The shadow camera starts just past the head's own geometry. From the block's centre
        // the projector's own model is in shot — most of it backface-culls away from inside,
        // but the handle bar's underside faced the lens and stamped a clean dark square into
        // the middle of every beam: the projector shadowing itself.
        Vec3 dir = ProjectorRenderer.lensDirection(be);
        Vec3 origin = Vec3.atCenterOf(be.getBlockPos()).add(dir.scale(0.85));

        // Symmetric square frustum wide enough to contain the (possibly shifted, keystoned,
        // wide) image frustum. Whatever the pad wastes in texels it buys in correctness.
        double fovV = be.getFov();
        double fovH = Math.toDegrees(2.0 * Math.atan(
                Math.tan(Math.toRadians(fovV * 0.5)) * be.getAspect()));
        double pad = 1.0 + 0.7 * Math.max(Math.abs(be.getKeystoneH()), Math.abs(be.getKeystoneV()))
                + 0.6 * Math.max(Math.abs(be.getShiftH()), Math.abs(be.getShiftV()));
        float fov = (float) Math.min(150.0, Math.max(30.0, Math.max(fovV, fovH) * pad * 1.1));

        // Angles in Minecraft's camera convention (pitch positive = down), derived from the
        // actual lens direction so the pass and the lookup can never disagree about aim.
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));

        // Rain and snow are camera-space streak planes; in a depth map they read as thousands
        // of tiny occluders peppering every surface with shadow noise. The beam ignores weather.
        float oldRain = mc.level.getRainLevel(1.0F);
        float oldThunder = mc.level.getThunderLevel(1.0F);
        mc.level.setRainLevel(0.0F);
        mc.level.setThunderLevel(0.0F);
        inDepthPass = true;
        try {
            CameraFeedManager.captureDepth(
                    new NdiCameraBlockEntity.ViewState(origin, yaw, pitch), fov, shadow.target);
        } finally {
            inDepthPass = false;
            mc.level.setRainLevel(oldRain);
            mc.level.setThunderLevel(oldThunder);
        }

        // The lookup matrix mirrors the pass EXACTLY. The view is built the way vanilla builds
        // its camera matrix — rotX(pitch) · rotY(yaw+180) · translate(−eye) — not a lookAt
        // reconstruction, so the compare cannot drift from the render by a convention.
        Matrix4f view = new Matrix4f()
                .rotationX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F))
                .translate((float) -origin.x, (float) -origin.y, (float) -origin.z);
        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(fov), 1.0F, 0.05F, mc.gameRenderer.getDepthFar());
        shadow.matrix = proj.mul(view);
        shadow.farPlane = mc.gameRenderer.getDepthFar();
        shadow.ready = true;
    }

    /** Dimension change / disconnect: all GPU targets go. */
    public static void clearAll() {
        for (Shadow s : SHADOWS.values()) {
            s.target.destroyBuffers();
        }
        SHADOWS.clear();
    }
}
