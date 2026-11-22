package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.fluid.CgFluids;
import com.crystalgraphics.mc.modern.platform.FluidsModern;

import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
//? if >=1.19 {
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
//?}

/**
 * {@link CgFluids} on Forge, client: {@code FluidAttributes} to 1.18.2, {@code IClientFluidTypeExtensions} from 1.19,
 * the name from Forge's own stack. Before 1.14.4 Forge's fluids are its own classes, not the registry's: none there.
 */
final class FluidsForge implements CgFluids.Host {

    @Override
    public String name(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        //? if <1.14.4 {
        /*return null;
        *///?} else {
        return f == null ? null : forge(f, stack).getDisplayName().getString();
        //?}
    }

    @Override
    public String stillSprite(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return null;
        //? if <1.14.4 {
        /*return null;
        *///?} elif <1.19 {
        /*return String.valueOf(f.getAttributes().getStillTexture());
        *///?} else {
        return String.valueOf(IClientFluidTypeExtensions.of(f).getStillTexture(forge(f, stack)));
        //?}
    }

    @Override
    public int tint(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return 0xFFFFFFFF;
        //? if <1.14.4 {
        /*return 0xFFFFFFFF;
        *///?} elif <1.19 {
        /*return FluidsModern.opaque(f.getAttributes().getColor(forge(f, stack)));
        *///?} else {
        return FluidsModern.opaque(IClientFluidTypeExtensions.of(f).getTintColor(forge(f, stack)));
        //?}
    }

    //? if >=1.14.4 {
    private static FluidStack forge(Fluid f, CgFluidStack stack) {
        return new FluidStack(f, Math.max(1, stack.millibuckets()));
    }
    //?}
}
