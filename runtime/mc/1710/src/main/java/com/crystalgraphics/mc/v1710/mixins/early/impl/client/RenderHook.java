package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.actor.HostRender1710;
import net.minecraft.client.renderer.entity.Render;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Every renderer's shadow, for {@code ActorRender1710}: the size and strength it copies. */
@Mixin(Render.class)
public abstract class RenderHook implements HostRender1710 {

    @Shadow
    protected float shadowSize;

    @Shadow
    protected float shadowOpaque;

    @Override
    public float crystalgraphics$shadowSize() {
        return shadowSize;
    }

    @Override
    public float crystalgraphics$shadowOpaque() {
        return shadowOpaque;
    }
}
