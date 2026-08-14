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

    public ShoulderCameraItem(Properties properties) {
        super(CAMERA_RIG, ArmorItem.Type.CHESTPLATE, properties);
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
