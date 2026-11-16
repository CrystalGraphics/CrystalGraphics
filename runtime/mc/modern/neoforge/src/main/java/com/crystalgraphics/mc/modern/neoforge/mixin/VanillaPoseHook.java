package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.mc.modern.platform.actor.VanillaPosesModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.2 {
/*import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
*///?} elif >=1.21.10 {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
*///?} elif >=1.21.3 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
*///?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
//?}
//? if >=1.21.11 <26.2 {
/*import net.minecraft.client.renderer.rendertype.RenderType;
*///?} elif >=1.21.10 <1.21.11 {
/*import net.minecraft.client.renderer.RenderType;
*///?}

// The engine's pose on a vanilla humanoid model (VanillaPosesModern), set right after the game sets the model up and
// before it and its armour draw: never at the tail of the model's own setup, which zombies, skeletons and players
// override after super. To 1.21.8 that is inside the living renderer's draw; from 1.21.10 inside the feature renderer
// that sets up each submitted model from its state. No refmap: each name in every runtime namespace, Mojang from
// 1.20.5, SRG (m_) on 1.20.2-1.20.4, with descriptors where a generic bridge shares the name.
@SuppressWarnings("rawtypes")
//? if >=1.21.10 {
/*@Mixin(value = ModelFeatureRenderer.class, remap = false)
*///?} else {
@Mixin(value = LivingEntityRenderer.class, remap = false)
//?}
public abstract class VanillaPoseHook {

    //? if >=26.2 {
    /*@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V",
            shift = At.Shift.AFTER), require = 1)
    private void crystalgraphics$posed(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
        VanillaPosesModern.posedState(submit.model(), submit.state());
    }
    *///?} elif >=1.21.10 {
    /*@Inject(method = "renderModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V",
            shift = At.Shift.AFTER), require = 1)
    private void crystalgraphics$posed(SubmitNodeStorage.ModelSubmit<?> submit, RenderType type, VertexConsumer consumer,
                                       OutlineBufferSource outlines, MultiBufferSource.BufferSource crumbling, CallbackInfo ci) {
        VanillaPosesModern.posedState(submit.model(), submit.state());
    }
    *///?} elif >=1.21.3 {
    /*@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V",
                    shift = At.Shift.AFTER), require = 1)
    private void crystalgraphics$posed(LivingEntityRenderState state, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        VanillaPosesModern.posedDrawn(((LivingEntityRenderer) (Object) this).getModel());
    }
    *///?} else {
    @Inject(method = {"render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            "m_7392_(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"},
            at = {@At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER),
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;m_6973_(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER)},
            require = 1)
    private void crystalgraphics$posed(LivingEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
                                       CallbackInfo ci) {
        VanillaPosesModern.posed(entity.getId(), ((LivingEntityRenderer) (Object) this).getModel());
    }
    //?}
}
