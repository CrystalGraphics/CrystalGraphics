package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.mc.modern.platform.actor.HostEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// The renderer's shadow fields by their SRG names, as an installed game has them (f_ 1.17-1.20.4, field_ 1.14.4-1.16.5).
// ShadowFieldsHook is the Mojang-named one; VariantMixins applies whichever the target class has.
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class ShadowFieldsSrgHook implements HostEntityRenderer {

    //? if <1.21.3 {
    //? if >=1.17 {
    @Shadow
    protected float f_114477_;

    @Override
    public float crystalgraphics$shadowRadius() {
        return f_114477_;
    }

    @Shadow
    protected float f_114478_;

    @Override
    public float crystalgraphics$shadowStrength() {
        return f_114478_;
    }
    //?} else {
    /*@Shadow
    protected float field_76989_e;

    @Override
    public float crystalgraphics$shadowRadius() {
        return field_76989_e;
    }

    @Shadow
    protected float field_76987_f;

    @Override
    public float crystalgraphics$shadowStrength() {
        return field_76987_f;
    }
    *///?}
    //?}
}
