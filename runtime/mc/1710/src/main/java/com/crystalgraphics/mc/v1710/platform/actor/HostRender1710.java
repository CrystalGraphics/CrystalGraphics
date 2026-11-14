package com.crystalgraphics.mc.v1710.platform.actor;

import net.minecraft.entity.EntityLivingBase;

/**
 * What {@link ActorRender1710} asks of the renderer it stands in for, which is protected: every {@code Render}'s shadow
 * through the {@code RenderHook} mixin, and a living entity's renderer's name tag through
 * {@code RendererLivingEntityHook} ({@link Living}).
 */
public interface HostRender1710 {

    float crystalgraphics$shadowSize();

    float crystalgraphics$shadowOpaque();

    /** A living entity's renderer: its name tag, Forge's {@code RenderLivingEvent.Specials} with it. */
    interface Living {
        void crystalgraphics$passSpecialRender(EntityLivingBase entity, double x, double y, double z);
    }
}
