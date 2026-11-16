package com.crystalgraphics.mc.v1710.platform.actor;

import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.world.actor.CgEntityActors;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The item an actor's entity holds, drawn where its body holds it ({@link CgEntityActors#grip}) by Minecraft's own item
 * renderer, every render pass of it, as {@code RenderBiped} draws a held item: what {@link ActorRender1710} adds in place
 * of the model's hand. 1.7.10 has one hand. The render thread.
 */
final class HeldItems1710 {

    private static final Matrix4f GRIP = new Matrix4f();
    private static final FloatBuffer MATRIX = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private HeldItems1710() {
    }

    /** {@code e}'s held item, the GL matrix at the camera and the entity at ({@code x}, {@code y}, {@code z}). */
    static void render(EntityLivingBase e, double x, double y, double z) {
        ItemStack stack = e.getHeldItem();
        if (stack == null || CgEntityActors.grip(e.getEntityId(), CgEntityProperties.MAIN, GRIP) == CgEntityActors.NO_GRIP) return;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GRIP.get(MATRIX);
        GL11.glMultMatrix(MATRIX);
        RenderManager rm = RenderManager.instance;
        if (stack.getItem().requiresMultipleRenderPasses()) {
            for (int pass = 0; pass < stack.getItem().getRenderPasses(stack.getItemDamage()); pass++) rm.itemRenderer.renderItem(e, stack, pass);
        } else {
            rm.itemRenderer.renderItem(e, stack, 0);
        }
        GL11.glPopMatrix();
    }
}
