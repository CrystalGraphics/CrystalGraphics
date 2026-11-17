package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.mc.modern.platform.actor.HostEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// The renderer's shadow fields by their Mojang names, where the game runs them (a dev run, Forge 1.20.6+). Mixin
// resolves an alias for a private field only, so each naming is its own mixin and VariantMixins applies the one the
// target class has: ShadowFieldsSrgHook is the other.
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class ShadowFieldsHook implements HostEntityRenderer {

    //? if <1.21.3 {
    @Shadow
    protected float shadowRadius;

    @Override
    public float crystalgraphics$shadowRadius() {
        return shadowRadius;
    }
    //?}

    //? if <1.21.4 {
    @Shadow
    protected float shadowStrength;

    @Override
    public float crystalgraphics$shadowStrength() {
        return shadowStrength;
    }
    //?}
}
