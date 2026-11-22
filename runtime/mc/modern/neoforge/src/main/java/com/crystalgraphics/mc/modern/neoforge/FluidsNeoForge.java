package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.fluid.CgFluids;
import com.crystalgraphics.mc.modern.platform.FluidsModern;

import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
//? if <26.1 {
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
//?}

/**
 * {@link CgFluids} on NeoForge, client: {@code IClientFluidTypeExtensions} to 1.21.11; from 26.1 the game's fluid
 * model's still sprite and its {@code FluidTintSource}.
 */
final class FluidsNeoForge implements CgFluids.Host {

    @Override
    public String name(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return null;
        //? if <1.20.6 {
        return neo(f, stack).getDisplayName().getString();
        //?} else {
        /*return neo(f, stack).getHoverName().getString();
        *///?}
    }

    @Override
    public String stillSprite(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return null;
        //? if <26.1 {
        return String.valueOf(IClientFluidTypeExtensions.of(f).getStillTexture(neo(f, stack)));
        //?} else {
        /*return FluidsModern.stillSprite(f);
        *///?}
    }

    @Override
    public int tint(CgFluidStack stack) {
        Fluid f = FluidsModern.fluid(stack);
        if (f == null) return 0xFFFFFFFF;
        //? if <26.1 {
        return FluidsModern.opaque(IClientFluidTypeExtensions.of(f).getTintColor(neo(f, stack)));
        //?} else {
        /*return FluidsModern.opaque(FluidsModern.model(f).fluidTintSource().colorAsStack(neo(f, stack)));
        *///?}
    }

    private static FluidStack neo(Fluid f, CgFluidStack stack) {
        return new FluidStack(f, Math.max(1, stack.millibuckets()));
    }
}
