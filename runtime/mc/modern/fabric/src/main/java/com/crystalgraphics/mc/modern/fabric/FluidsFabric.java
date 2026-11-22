package com.crystalgraphics.mc.modern.fabric;

import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.fluid.CgFluids;
import com.crystalgraphics.mc.modern.platform.FluidsModern;

import net.minecraft.world.level.material.Fluid;
//? if >=1.16.5 {
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
//?}
//? if >=1.16.5 <26.1 {
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//?}
//? if >=1.18.2 {
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
//?}

/**
 * {@link CgFluids} on Fabric, client: the transfer API's {@code FluidVariantRendering} and its attributes; from 26.1 the
 * game's fluid model's still sprite. Its name before 1.18.2 is the fluid's block's. Not provided before 1.16.5, which
 * has no fluid variants.
 */
final class FluidsFabric implements CgFluids.Host {

    @Override
    public String name(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return null;
        //? if <1.16.5 {
        /*return null;
        *///?} elif <1.18.2 {
        /*return f.defaultFluidState().createLegacyBlock().getBlock().getName().getString();
        *///?} else {
        return FluidVariantAttributes.getName(FluidVariant.of(f)).getString();
        //?}
    }

    @Override
    public String stillSprite(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return null;
        //? if <1.16.5 {
        /*return null;
        *///?} elif <1.19.3 {
        /*TextureAtlasSprite s = FluidVariantRendering.getSprite(FluidVariant.of(f));
        return s == null ? null : String.valueOf(s.getName());
        *///?} elif <26.1 {
        TextureAtlasSprite s = FluidVariantRendering.getSprite(FluidVariant.of(f));
        return s == null ? null : String.valueOf(s.contents().name());
        //?} else {
        /*return FluidsModern.stillSprite(f);
        *///?}
    }

    @Override
    public int tint(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return 0xFFFFFFFF;
        //? if <1.16.5 {
        /*return 0xFFFFFFFF;
        *///?} else {
        return FluidsModern.opaque(FluidVariantRendering.getColor(FluidVariant.of(f)));
        //?}
    }
}
