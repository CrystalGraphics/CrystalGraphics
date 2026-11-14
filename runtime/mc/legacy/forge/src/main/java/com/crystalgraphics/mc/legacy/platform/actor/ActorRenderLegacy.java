package com.crystalgraphics.mc.legacy.platform.actor;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The renderer an entity with an actor is drawn through instead of its own, Forge 1.8.9 to 1.12.2: its name tag, asked
 * of the real renderer, and the base renderer's shadow and fire at the real one's size; never its model. The
 * dispatcher draws the hitbox. One per real renderer, made the first time an actor's entity reaches it.
 */
public final class ActorRenderLegacy extends Render<Entity> {

    private static final Map<Render<?>, ActorRenderLegacy> MADE = new WeakHashMap<Render<?>, ActorRenderLegacy>();

    private final HostRender host;

    private ActorRenderLegacy(Render<?> real) {
        super(real.getRenderManager());
        this.host = (HostRender) real;
    }

    /** The renderer an actor's entity is drawn through in place of {@code real}. The render thread's. */
    static Render<?> of(Render<?> real) {
        if (real instanceof ActorRenderLegacy) return real;
        ActorRenderLegacy made = MADE.get(real);
        if (made == null) {
            made = new ActorRenderLegacy(real);
            MADE.put(real, made);
        }
        made.shadowSize = made.host.crystalgraphics$shadowSize();
        made.shadowOpaque = made.host.crystalgraphics$shadowOpaque();
        return made;
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return null;
    }

    @Override
    protected void renderName(Entity entity, double x, double y, double z) {
        host.crystalgraphics$renderName(entity, x, y, z);
    }
}
