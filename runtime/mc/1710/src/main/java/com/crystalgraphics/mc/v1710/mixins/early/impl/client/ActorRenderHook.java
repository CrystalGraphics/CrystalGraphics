package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.actor.Actors1710;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An entity with an actor drawn through {@code ActorRender1710}: its name tag, shadow, fire and hitbox, never its
 * model. The render manager looks the renderer up as it draws the entity ({@code func_147939_a}, which answers whether
 * it drew), so the lookup inside that draw is swapped.
 */
@Mixin(RenderManager.class)
public abstract class ActorRenderHook {

    @Inject(method = "func_147939_a", at = @At("HEAD"))
    private void crystalgraphics$drawing(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                         boolean hideBox, CallbackInfoReturnable<Boolean> cir) {
        Actors1710.drawing(entity);
    }

    @Inject(method = "func_147939_a", at = @At("RETURN"))
    private void crystalgraphics$drawn(Entity entity, double x, double y, double z, float yaw, float partialTicks,
                                       boolean hideBox, CallbackInfoReturnable<Boolean> cir) {
        Actors1710.drawn();
    }

    @Inject(method = "getEntityRenderObject", at = @At("RETURN"), cancellable = true)
    private void crystalgraphics$renderer(Entity entity, CallbackInfoReturnable<Render> cir) {
        cir.setReturnValue(Actors1710.renderer(entity, cir.getReturnValue()));
    }
}
