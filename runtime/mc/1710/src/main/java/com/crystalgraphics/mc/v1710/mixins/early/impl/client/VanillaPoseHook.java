package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.actor.VanillaPoses1710;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The engine's pose on a vanilla humanoid model ({@link VanillaPoses1710}), set in {@code ModelBiped.render} right
 * after it calls {@code setRotationAngles}, which zombies and skeletons override: so after theirs, and before any part
 * draws.
 */
@Mixin(ModelBiped.class)
public abstract class VanillaPoseHook {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelBiped;setRotationAngles(FFFFFFLnet/minecraft/entity/Entity;)V", shift = At.Shift.AFTER),
            require = 1)
    private void crystalgraphics$posed(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw,
                                       float headPitch, float scale, CallbackInfo ci) {
        VanillaPoses1710.posed((ModelBiped) (Object) this, entity);
    }
}
