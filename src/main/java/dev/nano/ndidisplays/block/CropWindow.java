package dev.nano.ndidisplays.block;

import net.minecraft.nbt.CompoundTag;

/**
 * The input window of a screen, video-processor style: the rectangle of the source
 * frame (in 0-1 uv space) that this screen displays. Full frame is (0,0)-(1,1);
 * a smaller window crops/zooms into the source, so several screens can each show
 * their own region of one shared feed — exactly how a real LED processor maps
 * one input across multiple outputs.
 */
public class CropWindow {

    /** Smallest window edge, so a degenerate window can never black a screen out. */
    public static final float MIN_SIZE = 0.05F;

    private float u0;
    private float v0;
    private float u1 = 1.0F;
    private float v1 = 1.0F;

    public float u0() {
        return u0;
    }

    public float v0() {
        return v0;
    }

    public float u1() {
        return u1;
    }

    public float v1() {
        return v1;
    }

    public float du() {
        return u1 - u0;
    }

    public float dv() {
        return v1 - v0;
    }

    public boolean isFull() {
        return u0 <= 0.0F && v0 <= 0.0F && u1 >= 1.0F && v1 >= 1.0F;
    }

    /** Sets the window, sanitising whatever arrives off the wire. */
    public void set(float newU0, float newV0, float newU1, float newV1) {
        float a = Clamps.f(Math.min(newU0, newU1), 0.0F, 1.0F, 0.0F);
        float b = Clamps.f(Math.max(newU0, newU1), 0.0F, 1.0F, 1.0F);
        float c = Clamps.f(Math.min(newV0, newV1), 0.0F, 1.0F, 0.0F);
        float d = Clamps.f(Math.max(newV0, newV1), 0.0F, 1.0F, 1.0F);
        if (b - a < MIN_SIZE) {
            b = Math.min(1.0F, a + MIN_SIZE);
            a = b - MIN_SIZE;
        }
        if (d - c < MIN_SIZE) {
            d = Math.min(1.0F, c + MIN_SIZE);
            c = d - MIN_SIZE;
        }
        u0 = a;
        u1 = b;
        v0 = c;
        v1 = d;
    }

    public void save(CompoundTag tag) {
        if (isFull()) {
            return;
        }
        tag.putFloat("CropU0", u0);
        tag.putFloat("CropV0", v0);
        tag.putFloat("CropU1", u1);
        tag.putFloat("CropV1", v1);
    }

    public void load(CompoundTag tag) {
        if (tag.contains("CropU0")) {
            set(tag.getFloat("CropU0"), tag.getFloat("CropV0"),
                    tag.getFloat("CropU1"), tag.getFloat("CropV1"));
        } else {
            u0 = 0.0F;
            v0 = 0.0F;
            u1 = 1.0F;
            v1 = 1.0F;
        }
    }
}
