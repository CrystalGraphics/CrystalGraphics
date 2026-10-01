package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.legacy.mixin.ActiveRenderInfoAccessor;
import com.crystalgraphics.render.stage.CgHostView;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * Minecraft 1.8–1.12's camera at its world hooks: the matrices its render info read back this pass, and the view
 * entity's interpolated position — what {@code RenderGlobal.renderEntities} hands the render manager, computed here
 * because that method skips its first two frames. The modelview carries the eye height and a third-person offset,
 * paired with that position at the entity's feet.
 */
public final class HostViewLegacy {

    private HostViewLegacy() {
    }

    public static void capture(Minecraft mc, float partialTicks, CgHostView out) {
        Entity entity = mc.getRenderViewEntity();
        out.set(entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks,
                entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks,
                entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks,
                ActiveRenderInfoAccessor.crystalgraphics$modelview(), ActiveRenderInfoAccessor.crystalgraphics$projection());
    }
}
