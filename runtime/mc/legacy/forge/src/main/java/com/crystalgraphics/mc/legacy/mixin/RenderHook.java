package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.actor.HostRender;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** What {@code ActorRenderLegacy} asks of the renderer it stands in for, at the SRG names every plateau shares. */
@Mixin(value = Render.class, remap = false)
public abstract class RenderHook implements HostRender {

    @Shadow(aliases = "field_76989_e")
    protected float shadowSize;

    @Shadow(aliases = "field_76987_f")
    protected float shadowOpaque;

    @Shadow(aliases = "func_177067_a")
    protected abstract void renderName(Entity entity, double x, double y, double z);

    @Override
    public void crystalgraphics$renderName(Entity entity, double x, double y, double z) {
        renderName(entity, x, y, z);
    }

    @Override
    public float crystalgraphics$shadowSize() {
        return shadowSize;
    }

    @Override
    public float crystalgraphics$shadowOpaque() {
        return shadowOpaque;
    }
}
