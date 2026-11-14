package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.mc.modern.platform.actor.ActorRendererModern;
import com.crystalgraphics.mc.modern.platform.actor.HostEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.17 {
import net.minecraft.client.renderer.entity.EntityRendererProvider;
//?} else {
/*import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
*///?}
//? if >=1.21.10 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//? if >=26.1 {
/^import net.minecraft.client.renderer.state.level.CameraRenderState;
^///?} else {
import net.minecraft.client.renderer.state.CameraRenderState;
//?}
*///?} elif >=1.21.3 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
*///?} elif >=1.16 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
//?} elif >=1.15 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}

// What ActorRendererModern asks of the renderer it stands in for (its protected name tag and shadow), and the context
// every renderer is made with, which ours are made with too. No refmap, so each name in every runtime namespace:
// Mojang 1.20.6+, SRG (m_, f_) 1.17-1.20.4, MCP (func_, field_) 1.14.4-1.16.5.
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class EntityRendererHook implements HostEntityRenderer {

    //? if >=1.17 {
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void crystalgraphics$made(EntityRendererProvider.Context context, CallbackInfo ci) {
        ActorRendererModern.constructed(context);
    }
    //?} else {
    /*@Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void crystalgraphics$made(EntityRenderDispatcher dispatcher, CallbackInfo ci) {
        ActorRendererModern.constructed(dispatcher);
    }
    *///?}

    //? if >=26.1 {
    /*@Shadow
    protected abstract void submitNameDisplay(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera);

    @Override
    public void crystalgraphics$submitNameTag(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera) {
        submitNameDisplay(state, poseStack, nodes, camera);
    }
    *///?} elif >=1.21.10 {
    /*@Shadow
    protected abstract void submitNameTag(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera);

    @Override
    public void crystalgraphics$submitNameTag(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera) {
        submitNameTag(state, poseStack, nodes, camera);
    }
    *///?} elif >=1.21.3 {
    /*@Shadow
    protected abstract float getShadowRadius(EntityRenderState state);

    @Shadow
    protected abstract void renderNameTag(EntityRenderState state, Component name, PoseStack poseStack, MultiBufferSource buffers, int light);

    @Override
    public float crystalgraphics$shadowRadius(EntityRenderState state) {
        return getShadowRadius(state);
    }

    @Override
    public void crystalgraphics$renderNameTag(EntityRenderState state, Component name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        renderNameTag(state, name, poseStack, buffers, light);
    }
    *///?} else {
    @Shadow(aliases = {"f_114477_", "field_76989_e"})
    protected float shadowRadius;

    @Override
    public float crystalgraphics$shadowRadius() {
        return shadowRadius;
    }

    //?}

    // A field to 1.21.3, asked of the state from 1.21.4; from 1.21.10 the state carries the shadow
    //? if >=1.21.4 <1.21.10 {
    /*@Shadow
    protected abstract float getShadowStrength(EntityRenderState state);

    @Override
    public float crystalgraphics$shadowStrength(EntityRenderState state) {
        return getShadowStrength(state);
    }
    *///?} elif <1.21.4 {
    @Shadow(aliases = {"f_114478_", "field_76987_f"})
    protected float shadowStrength;

    @Override
    public float crystalgraphics$shadowStrength() {
        return shadowStrength;
    }
    //?}

    //? if >=1.15 <1.21.3 {
    @Shadow(aliases = {"m_6512_", "func_177070_b"})
    protected abstract boolean shouldShowName(Entity entity);

    @Override
    public boolean crystalgraphics$shouldShowName(Entity entity) {
        return shouldShowName(entity);
    }
    //?}

    //? if >=1.20.6 <1.21.3 {
    /*@Shadow
    protected abstract void renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick);

    @Override
    public void crystalgraphics$renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick) {
        renderNameTag(entity, name, poseStack, buffers, light, partialTick);
    }
    *///?} elif >=1.16 <1.21.3 {
    @Shadow(aliases = {"m_7649_", "func_225629_a_"})
    protected abstract void renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light);

    @Override
    public void crystalgraphics$renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        renderNameTag(entity, name, poseStack, buffers, light);
    }
    //?} elif >=1.15 <1.21.3 {
    /*@Shadow(aliases = {"func_225629_a_"})
    protected abstract void renderNameTag(Entity entity, String name, PoseStack poseStack, MultiBufferSource buffers, int light);

    @Override
    public void crystalgraphics$renderNameTag(Entity entity, String name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        renderNameTag(entity, name, poseStack, buffers, light);
    }
    *///?} elif <1.15 {
    /*@Shadow(aliases = {"func_177067_a"})
    protected abstract void renderName(Entity entity, double x, double y, double z);

    @Override
    public void crystalgraphics$renderName(Entity entity, double x, double y, double z) {
        renderName(entity, x, y, z);
    }
    *///?}
}
