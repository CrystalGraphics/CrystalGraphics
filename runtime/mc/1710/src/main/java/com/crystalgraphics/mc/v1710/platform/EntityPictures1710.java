package com.crystalgraphics.mc.v1710.platform;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;

/**
 * {@link CgEntityPictures} on 1.7.10, client: the inventory's {@code func_147046_a} ported with a pose's angles in
 * place of a pointer's, as vanilla turns a model only toward a pointer (NR22 decision 2).
 */
public final class EntityPictures1710 implements CgEntityPictures.Host {

    private final EntityPoses1710 poses = new EntityPoses1710();

    @Override
    public String absent() {
        return null;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float ageTicks, float scale, float x, float y,
                     float w, float h, float centreY) {
        if (!(entity instanceof EntityLivingBase)) return;
        EntityLivingBase e = (EntityLivingBase) entity;
        // Depth on, as in the inventory screen
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // White, as the inventory screen leaves it: a tooltip's text colour would tint the model
        GL11.glColor4f(1, 1, 1, 1);
        ported(e, pose, ageTicks, Math.round(x + w / 2), Math.round(y + centreY + e.height * scale / 2), Math.round(scale));
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    // GuiInventory.func_147046_a (RFG's patched sources), its angles a pose's: the feet at (x, y), lifted by the entity's
    // yOffset, drawn at partial tick 1
    private void ported(EntityLivingBase e, CgEntityPose pose, float ageTicks, int x, int y, int scale) {
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, 50.0F);
        GL11.glScalef((float) -scale, (float) scale, (float) scale);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-pose.tilt(), 1.0F, 0.0F, 0.0F);
        poses.apply(e, pose.bodyYaw(), pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        GL11.glTranslatef(0.0F, e.yOffset, 0.0F);
        RenderManager.instance.playerViewY = 180.0F;
        try {
            RenderManager.instance.renderEntityWithPosYaw(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
        } finally {
            poses.restore(e);
            GL11.glPopMatrix();
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
    }

    // GuiInventory's: the player's feet at (51, 75) of the panel, 3 units over its 70-unit box's bottom, the pointer
    // measured from 50 units over them
    @Override
    public float inventoryCentreY(float h, float entityHeight, float scale) {
        return h - 3 - entityHeight * scale / 2;
    }

    @Override
    public float inventoryLookY(float h) {
        return h - 53;
    }
}
