package dev.nano.ndidisplays.block;

import dev.nano.ndidisplays.NdiDisplays;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * A video projector: not a screen, a light source with an image in it.
 *
 * Where every other block in this mod IS the display surface, the projector's display surface is
 * the world. It owns a full optical model — position, aim, lens angle, aspect, throw range,
 * keystone, lens shift — and the client renderer drapes its content onto whatever geometry the
 * frustum hits, with occlusion, exactly like pointing a real projector at a building.
 *
 * Everything here is the optical configuration; all the geometry work lives in the renderer.
 */
public class ProjectorBlockEntity extends BlockEntity {

    public static final int MAX_SOURCE_NAME = LedPanelBlockEntity.MAX_SOURCE_NAME;
    public static final int PATTERN_COUNT = LedPanelBlockEntity.PATTERN_COUNT;

    public static final float MIN_FOV = 10.0F;
    public static final float MAX_FOV = 120.0F;
    public static final float MIN_NEAR = 0.1F;
    public static final float MAX_NEAR = 8.0F;
    public static final float MIN_FAR = 2.0F;
    /** Throw ceiling. Also bounds the geometry scan, so it is a performance budget too. */
    public static final float MAX_FAR = 64.0F;
    public static final float MAX_KEYSTONE = 0.6F;
    public static final float MAX_SHIFT = 1.0F;
    public static final float MAX_FEATHER = 0.4F;

    private String sourceName = "";
    private int testPattern = 2;          // fresh projectors come up on the alignment grid
    private float yaw;                     // degrees, world-absolute; set from placement
    private float pitch;                   // degrees, -85..85, positive = up
    private float fov = 40.0F;             // vertical lens angle, degrees (zoom = this)
    private float aspect = 16.0F / 9.0F;
    private float near = 1.0F;
    private float far = 24.0F;
    private float keystoneH;               // horizontal keystone, image-plane tilt
    private float keystoneV;               // vertical keystone
    private float shiftH;                  // lens shift, fraction of frame
    private float shiftV;
    private float brightness = 0.9F;
    private float feather;                 // soft-edge width, fraction of frame
    private boolean additive = true;       // overlap blend: add light (true) or replace (false)
    private boolean showFrustum = true;    // calibration: draw the frustum wireframe

    // ------------------------------------------------------------------ client-only cache
    /** Bumped on every config change; the renderer rebuilds its surface mesh when it moves. */
    public transient int clientRevision;
    /** Renderer-owned drape mesh; lives here so it dies with the block entity. */
    public transient Object clientMesh;
    public transient long clientMeshBuiltAt;
    public transient int clientMeshRevision = -1;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(NdiDisplays.PROJECTOR_BE.get(), pos, state);
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getTestPattern() {
        return testPattern;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getFov() {
        return fov;
    }

    public float getAspect() {
        return aspect;
    }

    public float getNear() {
        return near;
    }

    public float getFar() {
        return far;
    }

    public float getKeystoneH() {
        return keystoneH;
    }

    public float getKeystoneV() {
        return keystoneV;
    }

    public float getShiftH() {
        return shiftH;
    }

    public float getShiftV() {
        return shiftV;
    }

    public float getBrightness() {
        return brightness;
    }

    public float getFeather() {
        return feather;
    }

    public boolean isAdditive() {
        return additive;
    }

    public boolean showFrustum() {
        return showFrustum;
    }

    /** Placement aims the projector the way the player is looking. */
    public void initAim(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = Clamps.f(pitch, -85.0F, 85.0F, 0.0F);
        setChanged();
    }

    /**
     * Applies GUI config. Handles the client sync packet path too, so every value is re-clamped
     * rather than trusted (see {@link LedPanelBlockEntity#load}).
     */
    public void applyConfig(String source, int pattern, float yaw, float pitch, float fov,
                            float aspect, float near, float far, float keystoneH, float keystoneV,
                            float shiftH, float shiftV, float brightness, float feather,
                            boolean additive, boolean showFrustum) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.testPattern = Clamps.i(pattern, 0, PATTERN_COUNT - 1);
        this.yaw = ((yaw % 360.0F) + 360.0F) % 360.0F;
        this.pitch = Clamps.f(pitch, -85.0F, 85.0F, 0.0F);
        this.fov = Clamps.f(fov, MIN_FOV, MAX_FOV, 40.0F);
        this.aspect = Clamps.f(aspect, 0.4F, 4.0F, 16.0F / 9.0F);
        this.near = Clamps.f(near, MIN_NEAR, MAX_NEAR, 1.0F);
        this.far = Clamps.f(far, Math.max(MIN_FAR, this.near + 0.5F), MAX_FAR, 24.0F);
        this.keystoneH = Clamps.f(keystoneH, -MAX_KEYSTONE, MAX_KEYSTONE, 0.0F);
        this.keystoneV = Clamps.f(keystoneV, -MAX_KEYSTONE, MAX_KEYSTONE, 0.0F);
        this.shiftH = Clamps.f(shiftH, -MAX_SHIFT, MAX_SHIFT, 0.0F);
        this.shiftV = Clamps.f(shiftV, -MAX_SHIFT, MAX_SHIFT, 0.0F);
        this.brightness = Clamps.f(brightness, 0.05F, 1.0F, 0.9F);
        this.feather = Clamps.f(feather, 0.0F, MAX_FEATHER, 0.0F);
        this.additive = additive;
        this.showFrustum = showFrustum;
        clientRevision++;
        setChanged();
    }

    /** NDI configuration card: switch to live video with the card's source. */
    public void applyNdiCard(String source) {
        this.sourceName = Clamps.name(source, MAX_SOURCE_NAME);
        this.testPattern = 0;
        clientRevision++;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Source", sourceName);
        tag.putInt("Pattern", testPattern);
        tag.putFloat("Yaw", yaw);
        tag.putFloat("Pitch", pitch);
        tag.putFloat("Fov", fov);
        tag.putFloat("Aspect", aspect);
        tag.putFloat("Near", near);
        tag.putFloat("Far", far);
        tag.putFloat("KeyH", keystoneH);
        tag.putFloat("KeyV", keystoneV);
        tag.putFloat("ShiftH", shiftH);
        tag.putFloat("ShiftV", shiftV);
        tag.putFloat("Brightness", brightness);
        tag.putFloat("Feather", feather);
        tag.putBoolean("Additive", additive);
        tag.putBoolean("Frustum", showFrustum);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        applyConfig(
                tag.getString("Source"),
                tag.contains("Pattern") ? tag.getInt("Pattern") : 2,
                tag.getFloat("Yaw"),
                tag.getFloat("Pitch"),
                tag.contains("Fov") ? tag.getFloat("Fov") : 40.0F,
                tag.contains("Aspect") ? tag.getFloat("Aspect") : 16.0F / 9.0F,
                tag.contains("Near") ? tag.getFloat("Near") : 1.0F,
                tag.contains("Far") ? tag.getFloat("Far") : 24.0F,
                tag.getFloat("KeyH"),
                tag.getFloat("KeyV"),
                tag.getFloat("ShiftH"),
                tag.getFloat("ShiftV"),
                tag.contains("Brightness") ? tag.getFloat("Brightness") : 0.9F,
                tag.getFloat("Feather"),
                !tag.contains("Additive") || tag.getBoolean("Additive"),
                !tag.contains("Frustum") || tag.getBoolean("Frustum"));
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) {
            load(packet.getTag());
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        // The drape can land anywhere in the throw, in any direction the head is aimed.
        return new AABB(worldPosition).inflate(far + 1.0);
    }
}
