package com.crystalgraphics.mc.v1710.platform.actor;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The renderer an entity with an actor is drawn through instead of its own, on 1.7.10: a living entity's name tag,
 * asked of its real renderer, and the base renderer's shadow and fire at the real one's size; never its model. The
 * render manager draws the hitbox. One per real renderer, made the first time an actor's entity reaches it.
 */
public final class ActorRender1710 extends Render {

    private static final Map<Render, ActorRender1710> MADE = new WeakHashMap<Render, ActorRender1710>();

    private final Render real;

    private ActorRender1710(Render real) {
        this.real = real;
        setRenderManager(RenderManager.instance);
    }

    /** The renderer an actor's entity is drawn through in place of {@code real}. The render thread's. */
    static Render of(Render real) {
        if (real instanceof ActorRender1710) return real;
        ActorRender1710 made = MADE.get(real);
        if (made == null) {
            made = new ActorRender1710(real);
            MADE.put(real, made);
        }
        HostRender1710 host = (HostRender1710) real;
        made.shadowSize = host.crystalgraphics$shadowSize();
        made.shadowOpaque = host.crystalgraphics$shadowOpaque();
        return made;
    }

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTicks) {
        if (real instanceof HostRender1710.Living && entity instanceof EntityLivingBase) {
            ((HostRender1710.Living) real).crystalgraphics$passSpecialRender((EntityLivingBase) entity, x, y, z);
        }
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return null;
    }
}
