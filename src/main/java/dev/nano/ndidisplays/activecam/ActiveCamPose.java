package dev.nano.ndidisplays.activecam;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Single source of truth for the aerial camera: position, velocity, and head.
 * Renderer, cables, NDI, snapshots and the path recorder all consume this.
 */
public final class ActiveCamPose {

    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    public float pan;
    public float tilt;
    public float roll;
    public float fov = 60.0F;

    public ActiveCamPose() {
    }

    public ActiveCamPose(Vec3 pos) {
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
    }

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    public Vec3 velocity() {
        return new Vec3(vx, vy, vz);
    }

    public void setPosition(Vec3 pos) {
        x = pos.x;
        y = pos.y;
        z = pos.z;
    }

    public void setVelocity(Vec3 vel) {
        vx = vel.x;
        vy = vel.y;
        vz = vel.z;
    }

    public ActiveCamPose copy() {
        ActiveCamPose out = new ActiveCamPose();
        out.set(this);
        return out;
    }

    public void set(ActiveCamPose other) {
        x = other.x;
        y = other.y;
        z = other.z;
        vx = other.vx;
        vy = other.vy;
        vz = other.vz;
        pan = other.pan;
        tilt = other.tilt;
        roll = other.roll;
        fov = other.fov;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putDouble("vx", vx);
        tag.putDouble("vy", vy);
        tag.putDouble("vz", vz);
        tag.putFloat("pan", pan);
        tag.putFloat("tilt", tilt);
        tag.putFloat("roll", roll);
        tag.putFloat("fov", fov);
        return tag;
    }

    public static ActiveCamPose load(CompoundTag tag) {
        ActiveCamPose pose = new ActiveCamPose();
        pose.x = tag.getDouble("x");
        pose.y = tag.getDouble("y");
        pose.z = tag.getDouble("z");
        pose.vx = tag.getDouble("vx");
        pose.vy = tag.getDouble("vy");
        pose.vz = tag.getDouble("vz");
        pose.pan = tag.getFloat("pan");
        pose.tilt = tag.getFloat("tilt");
        pose.roll = tag.getFloat("roll");
        pose.fov = tag.contains("fov") ? tag.getFloat("fov") : 60.0F;
        return pose;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(vx);
        buf.writeDouble(vy);
        buf.writeDouble(vz);
        buf.writeFloat(pan);
        buf.writeFloat(tilt);
        buf.writeFloat(roll);
        buf.writeFloat(fov);
    }

    public static ActiveCamPose read(FriendlyByteBuf buf) {
        ActiveCamPose pose = new ActiveCamPose();
        pose.x = buf.readDouble();
        pose.y = buf.readDouble();
        pose.z = buf.readDouble();
        pose.vx = buf.readDouble();
        pose.vy = buf.readDouble();
        pose.vz = buf.readDouble();
        pose.pan = buf.readFloat();
        pose.tilt = buf.readFloat();
        pose.roll = buf.readFloat();
        pose.fov = buf.readFloat();
        return pose;
    }

    /**
     * Cubic Hermite on position using velocities, linear on velocity, rotLerp on the head.
     * {@code dt} is the snapshot interval in seconds (scales the velocity handles).
     */
    public static ActiveCamPose hermite(ActiveCamPose a, ActiveCamPose b, float t, double dt) {
        t = Mth.clamp(t, 0.0F, 1.5F);
        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2 * t3 - 3 * t2 + 1;
        float h10 = t3 - 2 * t2 + t;
        float h01 = -2 * t3 + 3 * t2;
        float h11 = t3 - t2;
        ActiveCamPose out = new ActiveCamPose();
        out.x = h00 * a.x + h10 * (a.vx * dt) + h01 * b.x + h11 * (b.vx * dt);
        out.y = h00 * a.y + h10 * (a.vy * dt) + h01 * b.y + h11 * (b.vy * dt);
        out.z = h00 * a.z + h10 * (a.vz * dt) + h01 * b.z + h11 * (b.vz * dt);
        float u = Mth.clamp(t, 0.0F, 1.0F);
        out.vx = Mth.lerp(u, a.vx, b.vx);
        out.vy = Mth.lerp(u, a.vy, b.vy);
        out.vz = Mth.lerp(u, a.vz, b.vz);
        out.pan = Mth.rotLerp(u, a.pan, b.pan);
        out.tilt = Mth.lerp(u, a.tilt, b.tilt);
        out.roll = Mth.rotLerp(u, a.roll, b.roll);
        out.fov = Mth.lerp(u, a.fov, b.fov);
        return out;
    }
}
