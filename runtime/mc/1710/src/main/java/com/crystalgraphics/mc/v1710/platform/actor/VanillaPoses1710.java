package com.crystalgraphics.mc.v1710.platform.actor;

import com.crystalgraphics.mc.model.CgVanillaHumanoid;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;

/**
 * {@link CgVanillaPoses}' parts set on a {@link ModelBiped} on 1.7.10, once its angles are set up and before its parts
 * draw: {@code VanillaPoseHook} calls it from the model's draw. Armour models draw through the same method with the
 * same entity, so they follow; the headwear, which the model copies from the head, is copied again. The render
 * thread's; allocates nothing.
 */
public final class VanillaPoses1710 {

    private static final int STRIDE = CgVanillaHumanoid.STRIDE;
    private static final float[] PARTS = new float[CgVanillaHumanoid.PARTS.length * STRIDE];

    private VanillaPoses1710() {
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
        ModelRenderer head = model.bipedHead, hat = model.bipedHeadwear;
        hat.rotationPointX = head.rotationPointX;
        hat.rotationPointY = head.rotationPointY;
        hat.rotationPointZ = head.rotationPointZ;
        hat.rotateAngleX = head.rotateAngleX;
        hat.rotateAngleY = head.rotateAngleY;
        hat.rotateAngleZ = head.rotateAngleZ;
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
}
