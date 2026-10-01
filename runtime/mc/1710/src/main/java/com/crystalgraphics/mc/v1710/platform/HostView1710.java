package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.v1710.mixins.early.impl.client.ActiveRenderInfoAccessor;
import com.crystalgraphics.render.stage.CgHostView;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

/**
 * Minecraft 1.7.10's camera at its world hooks: the matrices its render info read back this frame, and the view
 * entity's interpolated position — what {@code RenderGlobal.renderEntities} puts in {@code RenderManager.renderPosX},
 * computed here because that method skips its first two frames. The modelview carries the eye height and a
 * third-person offset, paired with that position at the entity's feet.
 */
public final class HostView1710 {

    private HostView1710() {
    }

    public static void capture(Minecraft mc, float partialTicks, CgHostView out) {
        EntityLivingBase entity = mc.renderViewEntity;
        out.set(entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks,
                entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks,
                entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks,
                ActiveRenderInfoAccessor.crystalgraphics$modelview(), ActiveRenderInfoAccessor.crystalgraphics$projection());
    }
}
