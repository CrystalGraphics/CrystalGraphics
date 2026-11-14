package com.crystalgraphics.mc.modern.platform.actor;

import net.minecraft.world.entity.Entity;
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

/**
 * What {@link ActorRendererModern} asks the renderer it stands in for: its name tag and shadow, which are protected.
 * Every {@code EntityRenderer} implements it, through each loader's {@code EntityRendererHook}; the calls dispatch
 * virtually, so a player's renderer answers with its own name tag (the score below it included).
 */
public interface HostEntityRenderer {

    //? if >=1.21.10 {
    /*void crystalgraphics$submitNameTag(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera);
    *///?} elif >=1.21.3 {
    /*float crystalgraphics$shadowRadius(EntityRenderState state);

    void crystalgraphics$renderNameTag(EntityRenderState state, Component name, PoseStack poseStack, MultiBufferSource buffers, int light);
    *///?} else {
    float crystalgraphics$shadowRadius();
    //?}

    // A field to 1.21.3, asked of the state from 1.21.4; from 1.21.10 the state carries the shadow
    //? if >=1.21.4 <1.21.10 {
    /*float crystalgraphics$shadowStrength(EntityRenderState state);
    *///?} elif <1.21.4 {
    float crystalgraphics$shadowStrength();
    //?}

    //? if >=1.20.6 <1.21.3 {
    /*boolean crystalgraphics$shouldShowName(Entity entity);

    void crystalgraphics$renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick);
    *///?} elif >=1.16 <1.21.3 {
    boolean crystalgraphics$shouldShowName(Entity entity);

    void crystalgraphics$renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light);
    //?} elif >=1.15 <1.21.3 {
    /*boolean crystalgraphics$shouldShowName(Entity entity);

    void crystalgraphics$renderNameTag(Entity entity, String name, PoseStack poseStack, MultiBufferSource buffers, int light);
    *///?} elif <1.15 {
    /*void crystalgraphics$renderName(Entity entity, double x, double y, double z);
    *///?}
}
