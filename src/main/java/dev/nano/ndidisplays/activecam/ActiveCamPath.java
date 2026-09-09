package dev.nano.ndidisplays.activecam;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Timestamped motion-control take. Each sample stores a full {@link ActiveCamPose}
 * plus headMode / targetUUID so a later take can fly a path while the head tracks.
 */
public final class ActiveCamPath {

    public static final int MAX_SAMPLES = 2400;
    public static final float MAX_DURATION = 120.0F;

    public static final class Keyframe {
        public float t;
        public ActiveCamPose pose;
        public ActiveCamHeadMode headMode;
        @Nullable
        public UUID targetId;

        public Keyframe(float t, ActiveCamPose pose, ActiveCamHeadMode headMode, @Nullable UUID targetId) {
            this.t = t;
            this.pose = pose.copy();
            this.headMode = headMode == null ? ActiveCamHeadMode.RECORDED : headMode;
            this.targetId = targetId;
        }
    }

    public enum PlayMode {
        ONCE, LOOP;

        public PlayMode next() {
            return this == ONCE ? LOOP : ONCE;
        }
    }

    private final List<Keyframe> keys = new ArrayList<>();
    private PlayMode playMode = PlayMode.ONCE;
    private boolean playing;
    private boolean paused;
    private boolean recording;
    private float playTime;
    private float recordTime;

    public List<Keyframe> keys() {
        return Collections.unmodifiableList(keys);
    }

    public int size() {
        return keys.size();
    }

    public boolean isPlaying() {
        return playing && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isRecording() {
        return recording;
    }

    public PlayMode playMode() {
        return playMode;
    }

    public void setPlayMode(PlayMode mode) {
        this.playMode = mode == null ? PlayMode.ONCE : mode;
    }

    public float duration() {
        return keys.isEmpty() ? 0.0F : keys.get(keys.size() - 1).t;
    }

    public void startRecord() {
        keys.clear();
        recording = true;
        playing = false;
        paused = false;
        recordTime = 0.0F;
        playTime = 0.0F;
    }

    public void stopRecord() {
        recording = false;
    }

    public void sample(ActiveCamPose pose, ActiveCamHeadMode headMode, @Nullable UUID targetId) {
        if (!recording) {
            return;
        }
        if (keys.size() >= MAX_SAMPLES || recordTime > MAX_DURATION) {
            recording = false;
            return;
        }
        keys.add(new Keyframe(recordTime, pose, headMode, targetId));
        recordTime += ActiveCamKinematics.DT;
    }

    public void play() {
        if (keys.size() < 2) {
            return;
        }
        playing = true;
        paused = false;
        recording = false;
        playTime = 0.0F;
    }

    public void pause() {
        if (playing) {
            paused = !paused;
        }
    }

    public void stop() {
        playing = false;
        paused = false;
        recording = false;
        playTime = 0.0F;
    }

    public void clear() {
        stop();
        keys.clear();
    }

    /** Advances playhead; returns sampled pose (head still as recorded — caller may override). */
    @Nullable
    public Keyframe tick() {
        if (!isPlaying() || keys.size() < 2) {
            return null;
        }
        float end = duration();
        playTime += ActiveCamKinematics.DT;
        if (playTime > end) {
            if (playMode == PlayMode.LOOP) {
                playTime = playTime % Math.max(end, 0.001F);
            } else {
                playTime = end;
                playing = false;
                paused = false;
            }
        }
        return sampleAt(playTime);
    }

    public Keyframe sampleAt(float time) {
        if (keys.isEmpty()) {
            return new Keyframe(0.0F, new ActiveCamPose(), ActiveCamHeadMode.RECORDED, null);
        }
        if (keys.size() == 1 || time <= keys.get(0).t) {
            Keyframe k = keys.get(0);
            return new Keyframe(k.t, k.pose, k.headMode, k.targetId);
        }
        Keyframe last = keys.get(keys.size() - 1);
        if (time >= last.t) {
            return new Keyframe(last.t, last.pose, last.headMode, last.targetId);
        }
        int hi = 1;
        while (hi < keys.size() && keys.get(hi).t < time) {
            hi++;
        }
        Keyframe a = keys.get(hi - 1);
        Keyframe b = keys.get(hi);
        float span = Math.max(1.0e-4F, b.t - a.t);
        float u = (time - a.t) / span;
        ActiveCamPose pose = ActiveCamPose.hermite(a.pose, b.pose, u, span);
        return new Keyframe(time, pose, a.headMode, a.targetId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("playMode", playMode.name());
        tag.putBoolean("playing", playing);
        tag.putBoolean("paused", paused);
        tag.putFloat("playTime", playTime);
        ListTag list = new ListTag();
        for (Keyframe k : keys) {
            CompoundTag kt = k.pose.save();
            kt.putFloat("t", k.t);
            kt.putString("headMode", k.headMode.name());
            if (k.targetId != null) {
                kt.putUUID("target", k.targetId);
            }
            list.add(kt);
        }
        tag.put("keys", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        keys.clear();
        try {
            playMode = PlayMode.valueOf(tag.getString("playMode"));
        } catch (IllegalArgumentException ignored) {
            playMode = PlayMode.ONCE;
        }
        playing = tag.getBoolean("playing");
        paused = tag.getBoolean("paused");
        playTime = tag.getFloat("playTime");
        recording = false;
        ListTag list = tag.getList("keys", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && keys.size() < MAX_SAMPLES; i++) {
            CompoundTag kt = list.getCompound(i);
            ActiveCamHeadMode mode = ActiveCamHeadMode.RECORDED;
            try {
                mode = ActiveCamHeadMode.valueOf(kt.getString("headMode"));
            } catch (IllegalArgumentException ignored) {
            }
            UUID target = kt.hasUUID("target") ? kt.getUUID("target") : null;
            keys.add(new Keyframe(kt.getFloat("t"), ActiveCamPose.load(kt), mode, target));
        }
    }

    public static Vec3 lookVector(float pan, float tilt) {
        float yawRad = pan * Mth.DEG_TO_RAD;
        float pitchRad = tilt * Mth.DEG_TO_RAD;
        float cosP = Mth.cos(pitchRad);
        return new Vec3(-Mth.sin(yawRad) * cosP, -Mth.sin(pitchRad), Mth.cos(yawRad) * cosP);
    }
}
