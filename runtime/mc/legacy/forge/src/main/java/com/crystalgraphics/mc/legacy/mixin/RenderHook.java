package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.mc.legacy.platform.actor.HostRender;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** What {@code ActorRenderLegacy} asks of the renderer it stands in for, at the SRG names every plateau shares. */
@Mixin(value = Render.class, remap = false)
public abstract class RenderHook implements HostRender {

    // by their SRG names, as the installed game has them: Mixin resolves an alias for a private field only
    @Shadow
    protected float field_76989_e;

    @Shadow
    protected float field_76987_f;

    @Shadow
    protected abstract void func_177067_a(Entity entity, double x, double y, double z);

    @Override
    public void crystalgraphics$renderName(Entity entity, double x, double y, double z) {
        func_177067_a(entity, x, y, z);
    }

    @Override
    public float crystalgraphics$shadowSize() {
        return field_76989_e;
    }

    @Override
    public float crystalgraphics$shadowOpaque() {
        return field_76987_f;
    }
}
