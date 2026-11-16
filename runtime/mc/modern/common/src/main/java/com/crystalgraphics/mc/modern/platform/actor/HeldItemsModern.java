package com.crystalgraphics.mc.modern.platform.actor;

import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.world.actor.CgEntityActors;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
//? if >=1.21.10 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import java.util.Map;
import java.util.WeakHashMap;
*///?} elif >=1.21.3 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import java.util.Map;
import java.util.WeakHashMap;
*///?} elif >=1.19.4 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
//?} elif >=1.15 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
*///?}
//? if >=1.15 <1.20.6 {
import org.joml.Quaternionf;
import org.joml.Vector3f;
//?}
//? if >=1.15 <1.19.3 {
/*import com.mojang.math.Quaternion;
*///?}

/**
 * The items an actor's entity holds, drawn where its body holds them ({@link CgEntityActors#grip}) by Minecraft's own
 * item renderer, in its third-person display for the arm: what {@link ActorRendererModern} adds in place of the model's
 * hands. The client's render thread.
 *
 * <ul>
 *   <li>1.15 to 1.21.1 draw from the entity as it is drawn; 1.21.3 to 1.21.8 from the entity its state was taken from;
 *       from 1.21.10 each hand's item state is resolved as the entity's state is taken and submitted with it.</li>
 *   <li>Below 1.15 (Minecraft's fixed-function item path) the hands draw nothing.</li>
 * </ul>
 */
final class HeldItemsModern {

    private static final Matrix4f GRIP = new Matrix4f();
    //? if >=1.15 <1.20.6 {
    private static final Vector3f T = new Vector3f(), S = new Vector3f();
    private static final Quaternionf Q = new Quaternionf();
    //?}

    private HeldItemsModern() {
    }

    //? if >=1.21.10 {
    /*/^* A taken state's hands: each one's item state and grip, resolved as it was taken. ^/
    private static final class Held {
        final ItemStackRenderState[] items = {new ItemStackRenderState(), new ItemStackRenderState()};
        final Matrix4f[] grips = {new Matrix4f(), new Matrix4f()};
        final boolean[] drawn = new boolean[2];
    }

    private static final Map<EntityRenderState, Held> HELD = new WeakHashMap<>();

    /^* {@code entity}'s state was taken, its actor drawing it: each hand's item resolved where its body holds it. ^/
    static void extracted(Entity entity, EntityRenderState state) {
        if (!(entity instanceof LivingEntity living)) return;
        Held held = HELD.computeIfAbsent(state, s -> new Held());
        for (int hand = 0; hand < 2; hand++) {
            held.drawn[hand] = false;
            ItemStack stack = living.getItemInHand(hand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            if (stack.isEmpty()) continue;
            int arm = CgEntityActors.grip(entity.getId(), hand == 0 ? CgEntityProperties.MAIN : CgEntityProperties.OFF, held.grips[hand]);
            if (arm == CgEntityActors.NO_GRIP) continue;
            Minecraft.getInstance().getItemModelResolver().updateForLiving(held.items[hand], stack,
                    arm == CgEntityActors.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, living);
            held.drawn[hand] = !held.items[hand].isEmpty();
        }
    }

    /^* A taken state submitted: its hands' items with it. ^/
    static void submit(EntityRenderState state, PoseStack pose, SubmitNodeCollector nodes) {
        Held held = HELD.get(state);
        if (held == null) return;
        for (int hand = 0; hand < 2; hand++) {
            if (!held.drawn[hand]) continue;
            pose.pushPose();
            pose.mulPose(held.grips[hand]);
            held.items[hand].submit(pose, nodes, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }
    *///?} elif >=1.21.3 {
    /*/^* The entity each taken state came from, while its actor draws it. ^/
    private static final Map<EntityRenderState, LivingEntity> FROM = new WeakHashMap<>();

    static void extracted(Entity entity, EntityRenderState state) {
        if (entity instanceof LivingEntity living) FROM.put(state, living);
        else FROM.remove(state);
    }

    static void render(EntityRenderState state, PoseStack pose, MultiBufferSource buffers, int light) {
        LivingEntity living = FROM.get(state);
        if (living != null) render(living, pose, buffers, light);
    }
    *///?}

    //? if >=1.15 <1.21.10 {
    /** {@code entity}'s held items, its pose at the entity's feet. */
    static void render(Entity entity, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!(entity instanceof LivingEntity)) return;
        LivingEntity living = (LivingEntity) entity;
        hand(living, CgEntityProperties.MAIN, InteractionHand.MAIN_HAND, pose, buffers, light);
        hand(living, CgEntityProperties.OFF, InteractionHand.OFF_HAND, pose, buffers, light);
    }

    private static void hand(LivingEntity e, int hand, InteractionHand which, PoseStack pose, MultiBufferSource buffers, int light) {
        ItemStack stack = e.getItemInHand(which);
        if (stack.isEmpty()) return;
        int arm = CgEntityActors.grip(e.getId(), hand, GRIP);
        if (arm == CgEntityActors.NO_GRIP) return;
        boolean left = arm == CgEntityActors.LEFT;
        pose.pushPose();
        mul(pose, GRIP);
        //? if >=1.21.5 {
        /*Minecraft.getInstance().getItemRenderer().renderStatic(e, stack, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, pose, buffers, e.level(), light, OverlayTexture.NO_OVERLAY, e.getId());
        *///?} elif >=1.20.1 {
        Minecraft.getInstance().getItemRenderer().renderStatic(e, stack, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left, pose, buffers, e.level(), light, OverlayTexture.NO_OVERLAY, e.getId());
        //?} elif >=1.19.4 {
        /*Minecraft.getInstance().getItemRenderer().renderStatic(e, stack, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left, pose, buffers, e.level, light, OverlayTexture.NO_OVERLAY, e.getId());
        *///?} elif >=1.17.1 {
        /*Minecraft.getInstance().getItemRenderer().renderStatic(e, stack, left ? ItemTransforms.TransformType.THIRD_PERSON_LEFT_HAND
                : ItemTransforms.TransformType.THIRD_PERSON_RIGHT_HAND, left, pose, buffers, e.level, light, OverlayTexture.NO_OVERLAY, e.getId());
        *///?} else {
        /*Minecraft.getInstance().getItemRenderer().renderStatic(e, stack, left ? ItemTransforms.TransformType.THIRD_PERSON_LEFT_HAND
                : ItemTransforms.TransformType.THIRD_PERSON_RIGHT_HAND, left, pose, buffers, e.level, light, OverlayTexture.NO_OVERLAY);
        *///?}
        pose.popPose();
    }

    /** {@code pose} times {@code m}, its normals kept true. */
    private static void mul(PoseStack pose, Matrix4f m) {
        //? if >=1.20.6 {
        /*pose.mulPose(m);
        *///?} elif >=1.19.3 {
        m.getTranslation(T);
        m.getScale(S);
        m.getNormalizedRotation(Q);
        pose.translate(T.x, T.y, T.z);
        pose.mulPose(Q);
        pose.scale(S.x, S.y, S.z);
        //?} else {
        /*m.getTranslation(T);
        m.getScale(S);
        m.getNormalizedRotation(Q);
        pose.translate(T.x, T.y, T.z);
        pose.mulPose(new Quaternion(Q.x, Q.y, Q.z, Q.w));
        pose.scale(S.x, S.y, S.z);
        *///?}
    }
    //?}
}
