package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.actor.ActorsModern;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.10 {
/*import net.minecraft.client.renderer.entity.state.EntityRenderState;
*///?} elif >=1.21.3 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} elif >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

// An entity with an actor drawn through ActorRendererModern: its name tag, leash, shadow, fire and hitbox, never its
// model. To 1.21.8 the dispatcher looks the renderer up as it draws the entity, so the lookup inside that draw is
// swapped; from 1.21.10 it takes each entity's state first and looks the renderer up by state at submit. Named with
// descriptors where an overload shares the name. No refmap, so each name in both namespaces: Mojang (the dev run, and
// 26.1+) and intermediary.
@Mixin(value = EntityRenderDispatcher.class, remap = false)
public abstract class ActorRenderHook {

    //? if >=1.21.10 {
    /*@Inject(method = {"extractEntity", "method_72977"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$extracted(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        ActorsModern.extracted(entity, cir.getReturnValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = {"getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;", "method_68832"},
            at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$renderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer> cir) {
        cir.setReturnValue(ActorsModern.renderer(state, cir.getReturnValue()));
    }
    *///?} else {
    //? if >=1.21.3 {
    /*@Inject(method = {"render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", "method_62424"},
            at = @At("HEAD"), require = 1)
    private void crystalgraphics$drawing(Entity entity, double x, double y, double z, float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int light, CallbackInfo ci) {
        ActorsModern.drawing(entity);
    }

    @Inject(method = {"render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", "method_62424"},
            at = @At("RETURN"), require = 1)
    private void crystalgraphics$drawn(Entity entity, double x, double y, double z, float partialTick, PoseStack poseStack,
                                       MultiBufferSource buffers, int light, CallbackInfo ci) {
        ActorsModern.drawn();
    }
    *///?} elif >=1.15 {
    @Inject(method = {"render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            "method_3954"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$drawing(Entity entity, double x, double y, double z, float yaw, float partialTick, PoseStack poseStack,
                                         MultiBufferSource buffers, int light, CallbackInfo ci) {
        ActorsModern.drawing(entity);
    }

    @Inject(method = {"render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            "method_3954"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$drawn(Entity entity, double x, double y, double z, float yaw, float partialTick, PoseStack poseStack,
                                       MultiBufferSource buffers, int light, CallbackInfo ci) {
        ActorsModern.drawn();
    }
    //?} else {
    /*@Inject(method = {"render(Lnet/minecraft/world/entity/Entity;DDDFFZ)V", "method_3954"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$drawing(Entity entity, double x, double y, double z, float yaw, float partialTick, boolean debug,
                                         CallbackInfo ci) {
        ActorsModern.drawing(entity);
    }

    @Inject(method = {"render(Lnet/minecraft/world/entity/Entity;DDDFFZ)V", "method_3954"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$drawn(Entity entity, double x, double y, double z, float yaw, float partialTick, boolean debug,
                                       CallbackInfo ci) {
        ActorsModern.drawn();
    }
    *///?}

    @SuppressWarnings({"rawtypes", "unchecked"})
    //? if >=1.15 {
    @Inject(method = {"getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            "method_3953"}, at = @At("RETURN"), cancellable = true, require = 1)
    //?} else {
    /*@Inject(method = {"getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            "method_3957"}, at = @At("RETURN"), cancellable = true, require = 1)
    *///?}
    private void crystalgraphics$renderer(Entity entity, CallbackInfoReturnable<EntityRenderer> cir) {
        cir.setReturnValue(ActorsModern.renderer(entity, cir.getReturnValue()));
    }
    //?}
}
