package dev.nano.ndidisplays.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * A shoulder-mounted camera rig, worn rather than carried: while it is in the chest slot the
 * operator's own view goes out as an NDI source, leaving both hands free — which is the whole
 * point of a shoulder rig over the handheld camera.
 *
 * An {@link ArmorItem} purely to claim the chest slot. It grants no protection: this is
 * broadcast kit, and a camera that quietly doubled as a chestplate would be a balance change
 * nobody asked for. Durability is high and it is unenchantable.
 */
public class ShoulderCameraItem extends ArmorItem {

    /**
     * Cosmetic-only material. Not a registry object in 1.20.1 — {@link ArmorItem} takes the
     * interface directly — so it needs no registration, but the name must match the texture
     * at {@code textures/models/armor/camera_rig_layer_1.png}.
     */
    public static final ArmorMaterial CAMERA_RIG = new ArmorMaterial() {
        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            return 512;
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return 0;
        }

        @Override
        public int getEnchantmentValue() {
            return 0;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_IRON;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }

        @Override
        public String getName() {
            return "camera_rig";
        }

        @Override
        public float getToughness() {
            return 0.0F;
        }

        @Override
        public float getKnockbackResistance() {
            return 0.0F;
        }
    };

    // --- rig aim, stored on the stack so it rides with the item and syncs for free ---

    private static final String TAG_PAN = "RigPan";
    private static final String TAG_TILT = "RigTilt";
    private static final String TAG_FOV = "RigFov";

    /** Pan limit either side of the operator's facing, degrees. */
    public static final float MAX_PAN = 90.0F;
    /** Tilt limit above and below level, degrees. */
    public static final float MAX_TILT = 60.0F;
    public static final float MIN_FOV = 15.0F;
    public static final float MAX_FOV = 110.0F;
    public static final float DEFAULT_FOV = 55.0F;

    // --- feed settings, the same set the block cameras expose, stored the same way ---

    private static final String TAG_SOURCE = "RigSource";
    private static final String TAG_LIVE = "RigLive";
    private static final String TAG_RES = "RigRes";
    private static final String TAG_FPS = "RigFps";

    /** Index into {@link dev.nano.ndidisplays.block.NdiCameraBlockEntity#RES_W}: 720p. */
    public static final int DEFAULT_RESOLUTION = 1;
    public static final int DEFAULT_FPS = 30;
    public static final int MAX_SOURCE_LENGTH = 128;

    /** Operator's own NDI source name; empty means the default "MC Shoulder <player>". */
    public static String source(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(TAG_SOURCE);
    }

    /** Whether the rig broadcasts while worn. Defaults on, so an untouched rig just works. */
    public static boolean live(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(TAG_LIVE) || tag.getBoolean(TAG_LIVE);
    }

    public static int resolutionIndex(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_RES)) {
            return DEFAULT_RESOLUTION;
        }
        return dev.nano.ndidisplays.block.Clamps.i(tag.getInt(TAG_RES), 0,
                dev.nano.ndidisplays.block.NdiCameraBlockEntity.RES_W.length - 1);
    }

    public static int width(net.minecraft.world.item.ItemStack stack) {
        return dev.nano.ndidisplays.block.NdiCameraBlockEntity.RES_W[resolutionIndex(stack)];
    }

    public static int height(net.minecraft.world.item.ItemStack stack) {
        return dev.nano.ndidisplays.block.NdiCameraBlockEntity.RES_H[resolutionIndex(stack)];
    }

    public static int fps(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_FPS)) {
            return DEFAULT_FPS;
        }
        return dev.nano.ndidisplays.block.Clamps.i(tag.getInt(TAG_FPS), 1, 120);
    }

    /**
     * Server-side: stores the feed settings from the rig's config screen, re-clamped.
     *
     * Pan and tilt are zeroed here on purpose. The screen no longer shows them — the rig
     * simply follows where the operator looks, like the handheld — so a trim left over from
     * an older build would be invisible state that quietly skews every shot.
     */
    public static void setConfig(net.minecraft.world.item.ItemStack stack, String source,
                                 boolean live, int resolution, int fps, float fov) {
        net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
        String name = source == null ? "" : source.trim();
        if (name.length() > MAX_SOURCE_LENGTH) {
            name = name.substring(0, MAX_SOURCE_LENGTH);
        }
        tag.putString(TAG_SOURCE, name);
        tag.putBoolean(TAG_LIVE, live);
        tag.putInt(TAG_RES, dev.nano.ndidisplays.block.Clamps.i(resolution, 0,
                dev.nano.ndidisplays.block.NdiCameraBlockEntity.RES_W.length - 1));
        tag.putInt(TAG_FPS, dev.nano.ndidisplays.block.Clamps.i(fps, 1, 120));
        setAim(stack, 0.0F, 0.0F, fov);
    }

    public static float pan(net.minecraft.world.item.ItemStack stack) {
        return read(stack, TAG_PAN, 0.0F, -MAX_PAN, MAX_PAN);
    }

    public static float tilt(net.minecraft.world.item.ItemStack stack) {
        return read(stack, TAG_TILT, 0.0F, -MAX_TILT, MAX_TILT);
    }

    public static float fov(net.minecraft.world.item.ItemStack stack) {
        return read(stack, TAG_FOV, DEFAULT_FOV, MIN_FOV, MAX_FOV);
    }

    private static float read(net.minecraft.world.item.ItemStack stack, String key,
                             float fallback, float min, float max) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(key)) {
            return fallback;
        }
        return dev.nano.ndidisplays.block.Clamps.f(tag.getFloat(key), min, max, fallback);
    }

    /** Server-side: stores a new aim on the worn stack, re-clamped. */
    public static void setAim(net.minecraft.world.item.ItemStack stack,
                              float pan, float tilt, float fov) {
        net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
        tag.putFloat(TAG_PAN, dev.nano.ndidisplays.block.Clamps.f(pan, -MAX_PAN, MAX_PAN, 0.0F));
        tag.putFloat(TAG_TILT, dev.nano.ndidisplays.block.Clamps.f(tilt, -MAX_TILT, MAX_TILT, 0.0F));
        tag.putFloat(TAG_FOV, dev.nano.ndidisplays.block.Clamps.f(fov, MIN_FOV, MAX_FOV, DEFAULT_FOV));
    }

    public ShoulderCameraItem(Properties properties) {
        super(CAMERA_RIG, ArmorItem.Type.CHESTPLATE, properties);
    }

    /**
     * Sneak + right-click opens the rig's controls, so the camera can be aimed independently
     * of where the operator is looking — which is the whole point of a shoulder mount.
     *
     * Works whether the rig is worn or in hand: aiming it while wearing it is the normal case,
     * but being able to set it up before putting it on is convenient.
     */
    @Override
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(
            net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand) {
        net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return net.minecraft.world.InteractionResultHolder.pass(held);
        }
        if (level.isClientSide) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () ->
                            dev.nano.ndidisplays.client.ClientHooks.openShoulderRigConfig());
        }
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    /**
     * Custom armour texture. Vanilla derives the path from the material name as
     * {@code minecraft:textures/models/armor/<name>_layer_1.png}, which can never resolve a
     * texture shipped in this mod's namespace — that mismatch is what renders the rig as
     * missing-texture magenta. Forge's hook is the supported way to point at our own file.
     */
    @Override
    public String getArmorTexture(net.minecraft.world.item.ItemStack stack,
                                  net.minecraft.world.entity.Entity entity,
                                  net.minecraft.world.entity.EquipmentSlot slot, String type) {
        return "ndidisplays:textures/models/armor/camera_rig_layer_1.png";
    }

    /**
     * Hands the worn rig's model to the armour layer. Forge 1.20.1 asks the item for its
     * client extensions rather than exposing a registration event, and only ever calls this
     * on the client — which is what keeps the client-only model class off the server.
     */
    @Override
    public void initializeClient(java.util.function.Consumer<
            net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
            @Override
            public net.minecraft.client.model.HumanoidModel<?> getHumanoidArmorModel(
                    net.minecraft.world.entity.LivingEntity entity,
                    net.minecraft.world.item.ItemStack stack,
                    net.minecraft.world.entity.EquipmentSlot slot,
                    net.minecraft.client.model.HumanoidModel<?> original) {
                dev.nano.ndidisplays.client.render.ShoulderRigModel rig =
                        dev.nano.ndidisplays.client.ClientSetup.shoulderRig();
                // Copying the original's pose carries body rotation, sneak lean and riding
                // onto the rig; without it the camera floats bolt upright while the operator
                // crouches. The cast is safe — the layer always passes a humanoid model, and
                // only the shared HumanoidModel fields are read.
                @SuppressWarnings("unchecked")
                net.minecraft.client.model.HumanoidModel<net.minecraft.world.entity.LivingEntity> from =
                        (net.minecraft.client.model.HumanoidModel<
                                net.minecraft.world.entity.LivingEntity>) original;
                from.copyPropertiesTo(rig);
                return rig;
            }
        });
    }
}
