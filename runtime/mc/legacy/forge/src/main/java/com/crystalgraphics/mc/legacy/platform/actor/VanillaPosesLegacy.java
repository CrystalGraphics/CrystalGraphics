package com.crystalgraphics.mc.legacy.platform.actor;

import com.crystalgraphics.mc.model.CgVanillaHumanoid;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;

/**
 * {@link CgVanillaPoses}' parts set on a {@link ModelBiped} on Forge 1.8.9 to 1.12.2, once its angles are set up and
 * before its parts draw: {@code VanillaPoseHook} calls it from the model's draw. Armour models draw through the same
 * method with the same entity, so they follow. The render thread's; allocates nothing.
 *
 * <ul>
 *   <li>The headwear and a player's wear (sleeves, trousers, jacket) are parts of their own the model copies from the
 *       limbs as it sets its angles: copied again here.</li>
 * </ul>
 */
public final class VanillaPosesLegacy {

    private static final int STRIDE = CgVanillaHumanoid.STRIDE;
    private static final float[] PARTS = new float[CgVanillaHumanoid.PARTS.length * STRIDE];

    private VanillaPosesLegacy() {
    }

    /** {@code model}, its angles just set for {@code entity}, takes the engine's pose where the engine poses it. */
    public static void posed(ModelBiped model, Entity entity) {
        if (entity == null || !CgVanillaPoses.parts(entity.getEntityId(), PARTS)) return;
        set(model.bipedHead, CgVanillaHumanoid.HEAD);
        set(model.bipedBody, CgVanillaHumanoid.BODY);
        set(model.bipedRightArm, CgVanillaHumanoid.RIGHT_ARM);
        set(model.bipedLeftArm, CgVanillaHumanoid.LEFT_ARM);
        set(model.bipedRightLeg, CgVanillaHumanoid.RIGHT_LEG);
        set(model.bipedLeftLeg, CgVanillaHumanoid.LEFT_LEG);
        copy(model.bipedHead, model.bipedHeadwear);
        if (model instanceof ModelPlayer) {
            ModelPlayer p = (ModelPlayer) model;
            copy(p.bipedLeftArm, p.bipedLeftArmwear);
            copy(p.bipedRightArm, p.bipedRightArmwear);
            copy(p.bipedLeftLeg, p.bipedLeftLegwear);
            copy(p.bipedRightLeg, p.bipedRightLegwear);
            copy(p.bipedBody, p.bipedBodyWear);
        }
    }

    private static void set(ModelRenderer part, int index) {
        int o = index * STRIDE;
        part.rotationPointX = PARTS[o];
        part.rotationPointY = PARTS[o + 1];
        part.rotationPointZ = PARTS[o + 2];
        part.rotateAngleX = PARTS[o + 3];
        part.rotateAngleY = PARTS[o + 4];
        part.rotateAngleZ = PARTS[o + 5];
    }

    private static void copy(ModelRenderer from, ModelRenderer to) {
        to.rotationPointX = from.rotationPointX;
        to.rotationPointY = from.rotationPointY;
        to.rotationPointZ = from.rotationPointZ;
        to.rotateAngleX = from.rotateAngleX;
        to.rotateAngleY = from.rotateAngleY;
        to.rotateAngleZ = from.rotateAngleZ;
    }
}
