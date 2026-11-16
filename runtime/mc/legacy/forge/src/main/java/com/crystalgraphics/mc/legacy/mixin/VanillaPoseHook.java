package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.actor.VanillaPosesLegacy;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's pose on a vanilla humanoid model ({@link VanillaPosesLegacy}), set in {@code ModelBiped.render} right
 * after it calls {@code setRotationAngles}, which zombies, skeletons and players override: so after theirs, and before
 * any part draws. At SRG names: {@code func_78088_a} the draw, {@code func_78087_a} the angles.
 */
@Mixin(value = ModelBiped.class, remap = false)
public abstract class VanillaPoseHook {

    @Inject(method = "func_78088_a", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelBiped;func_78087_a(FFFFFFLnet/minecraft/entity/Entity;)V", shift = At.Shift.AFTER),
            require = 1)
    private void crystalgraphics$posed(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw,
                                       float headPitch, float scale, CallbackInfo ci) {
        VanillaPosesLegacy.posed((ModelBiped) (Object) this, entity);
    }
}
