package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.actor.ActorsLegacy;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.9 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * An entity with an actor drawn through {@code ActorRenderLegacy}: its name tag, shadow, fire and hitbox, never its
 * model. The render manager looks the renderer up as it draws the entity, so the lookup inside that draw is swapped. At
 * SRG names: the draw is {@code func_147939_a} on 1.8.9 (it answers whether it drew), {@code func_188391_a} after.
 */
@Mixin(value = RenderManager.class, remap = false)
public abstract class ActorRenderHook {

    //? if >=1.9 {
    @Inject(method = "func_188391_a", at = @At("HEAD"), require = 1)
    private void crystalgraphics$drawing(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                         boolean hideBox, CallbackInfo ci) {
        ActorsLegacy.drawing(entity);
    }

    @Inject(method = "func_188391_a", at = @At("RETURN"), require = 1)
    private void crystalgraphics$drawn(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                       boolean hideBox, CallbackInfo ci) {
        ActorsLegacy.drawn();
    }
    //?} else {
    /*@Inject(method = "func_147939_a", at = @At("HEAD"), require = 1)
    private void crystalgraphics$drawing(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                         boolean hideBox, CallbackInfoReturnable<Boolean> cir) {
        ActorsLegacy.drawing(entity);
    }

    @Inject(method = "func_147939_a", at = @At("RETURN"), require = 1)
    private void crystalgraphics$drawn(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                       boolean hideBox, CallbackInfoReturnable<Boolean> cir) {
        ActorsLegacy.drawn();
    }
    *///?}

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "func_78713_a", at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$renderer(Entity entity, CallbackInfoReturnable<Render> cir) {
        cir.setReturnValue(ActorsLegacy.renderer(entity, cir.getReturnValue()));
    }
}
