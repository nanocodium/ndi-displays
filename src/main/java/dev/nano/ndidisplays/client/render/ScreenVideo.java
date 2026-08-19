package dev.nano.ndidisplays.client.render;

import dev.nano.ndidisplays.client.CameraFeedManager;
import dev.nano.ndidisplays.client.ndi.NdiManager;
import dev.nano.ndidisplays.client.ndi.NdiStream;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolves the GL texture a screen should sample this frame.
 *
 * While an in-game camera or drone is capturing, screens that show that same
 * source must not bind the live NDI texture: the capture would film the wall
 * that is already showing the capture, and the LED lattice aliases into the
 * thick black diagonal hatch the player sees on the wall.
 */
public final class ScreenVideo {

    private ScreenVideo() {
    }

    public static boolean suppressLive(String sourceName) {
        return CameraFeedManager.isCapturingOwnSource(sourceName);
    }

    /** LED bezel gap: none during a capture so the lattice is not filmed. */
    public static float ledGap(float gap) {
        return CameraFeedManager.isCapturing() ? 0.0F : gap;
    }

    /** Per-LED calibration noise: off during a capture for the same reason. */
    public static float ledVariance(float variance) {
        return CameraFeedManager.isCapturing() ? 0.0F : variance;
    }

    public static int textureId(String sourceName) {
        if (suppressLive(sourceName)) {
            return FallbackTextures.black();
        }
        NdiStream stream = NdiManager.acquire(sourceName);
        if (stream != null) {
            stream.uploadIfNeeded();
            int id = stream.getTextureId();
            if (id != 0) {
                return id;
            }
        }
        return FallbackTextures.black();
    }

    /** Null when there is no frame (or the live source is suppressed). */
    public static ResourceLocation textureLocation(String sourceName) {
        if (suppressLive(sourceName)) {
            return null;
        }
        NdiStream stream = NdiManager.acquire(sourceName);
        if (stream != null) {
            stream.uploadIfNeeded();
            return stream.getTextureLocation();
        }
        return null;
    }
}
