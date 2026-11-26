package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;

/**
 * {@link CgEntityPictures} on legacy Forge, client: the inventory's {@code drawEntityOnScreen} ported with a pose's
 * angles in place of a pointer's, as vanilla turns a model only toward a pointer (NR22 decision 2).
 */
public final class EntityPicturesLegacy implements CgEntityPictures.Host {

    private final EntityPosesLegacy poses = new EntityPosesLegacy();

    @Override
    public String absent() {
        return null;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float ageTicks, float scale, float x, float y,
                     float w, float h, float centreY) {
        if (!(entity instanceof EntityLivingBase)) return;
        EntityLivingBase e = (EntityLivingBase) entity;
        // Depth on, as in the inventory screen: with it off a model's far side shows
        GlStateManager.enableDepth();
        // White, as the inventory screen leaves it: a tooltip's text colour would tint the model
        GlStateManager.color(1, 1, 1, 1);
        // NO NAMETAG OF THE GAME'S, as its inventory draws none: a UI draws its own. A living renderer skips it while the
        // GUI is hidden (canRenderName)
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        boolean hidden = settings.hideGUI;
        settings.hideGUI = true;
        try {
            ported(e, pose, ageTicks, Math.round(x + w / 2), Math.round(y + centreY + e.height * scale / 2), Math.round(scale));
        } finally {
            settings.hideGUI = hidden;
        }
        GlStateManager.disableDepth();
    }

    // GuiInventory.drawEntityOnScreen (javap of the MCP-named 1.8.9 and 1.12.2 jars), its angles a pose's: the feet at
    // (x, y), drawn at partial tick 1 with no shadow
    private void ported(EntityLivingBase e, CgEntityPose pose, float ageTicks, int x, int y, int scale) {
        GlStateManager.enableColorMaterial();
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, 50.0F);
        GlStateManager.scale((float) -scale, (float) scale, (float) scale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-pose.tilt(), 1.0F, 0.0F, 0.0F);
        poses.apply(e, pose.bodyYaw(), pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        RenderManager manager = Minecraft.getMinecraft().getRenderManager();
        manager.setPlayerViewY(180.0F);
        manager.setRenderShadow(false);
        try {
            //? if <1.10 {
            /*manager.renderEntityWithPosYaw(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
            *///?} elif <1.12 {
            /*manager.doRenderEntity(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
            *///?} else {
            manager.renderEntity(e, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
            //?}
        } finally {
            manager.setRenderShadow(true);
            poses.restore(e);
            GlStateManager.popMatrix();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
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
