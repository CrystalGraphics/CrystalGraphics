package com.crystalgraphics.mc.legacy.platform.actor;

import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.world.actor.CgEntityActors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The items an actor's entity holds, drawn where its body holds them ({@link CgEntityActors#grip}) by Minecraft's own
 * item renderer in its third-person display, Forge 1.8.9 to 1.12.2: what {@link ActorRenderLegacy} adds in place of the
 * model's hands. 1.8.9 has one hand, held in the right arm's place unless the body is left-handed. The render thread.
 */
final class HeldItemsLegacy {

    private static final Matrix4f GRIP = new Matrix4f();
    private static final FloatBuffer MATRIX = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private HeldItemsLegacy() {
    }

    /** {@code entity}'s held items, the GL matrix at the camera and the entity at ({@code x}, {@code y}, {@code z}). */
    static void render(Entity entity, double x, double y, double z) {
        if (!(entity instanceof EntityLivingBase)) return;
        EntityLivingBase e = (EntityLivingBase) entity;
        //? if >=1.10 {
        hand(e, CgEntityProperties.MAIN, e.getHeldItemMainhand(), x, y, z);
        hand(e, CgEntityProperties.OFF, e.getHeldItemOffhand(), x, y, z);
        //?} else {
        /*hand(e, CgEntityProperties.MAIN, e.getHeldItem(), x, y, z);
        *///?}
    }

    private static void hand(EntityLivingBase e, int hand, ItemStack stack, double x, double y, double z) {
        //? if >=1.12 {
        if (stack.isEmpty()) return;
        //?} else {
        /*if (stack == null) return;
        *///?}
        int arm = CgEntityActors.grip(e.getEntityId(), hand, GRIP);
        if (arm == CgEntityActors.NO_GRIP) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GRIP.get(MATRIX);
        GlStateManager.multMatrix(MATRIX);
        //? if >=1.10 {
        boolean left = arm == CgEntityActors.LEFT;
        Minecraft.getMinecraft().getItemRenderer().renderItemSide(e, stack, left ? ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND
                : ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND, left);
        //?} else {
        /*Minecraft.getMinecraft().getItemRenderer().renderItem(e, stack, ItemCameraTransforms.TransformType.THIRD_PERSON);
        *///?}
        GlStateManager.popMatrix();
    }
}
