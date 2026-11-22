package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.fluid.CgFluids;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * {@link CgFluids} on legacy Forge, client: Forge's {@code FluidRegistry}. A fluid's name there is Forge's own
 * ({@code water}); a value's {@code minecraft:} is taken off to look it up.
 */
final class FluidsLegacy implements CgFluids.Host {

    @Override
    public String name(CgFluidStack stack) {
        Fluid f = fluid(stack);
        return f == null ? null : f.getLocalizedName(forge(f, stack));
    }

    @Override
    public String stillSprite(CgFluidStack stack) {
        Fluid f = fluid(stack);
        ResourceLocation still = f == null ? null : f.getStill(forge(f, stack));
        return still == null ? null : still.toString();
    }

    @Override
    public int tint(CgFluidStack stack) {
        Fluid f = fluid(stack);
        return f == null ? 0xFFFFFFFF : opaque(f.getColor(forge(f, stack)));
    }

    private static Fluid fluid(CgFluidStack stack) {
        return FluidRegistry.getFluid(stack.fluid().replaceFirst("^minecraft:", ""));
    }

    private static FluidStack forge(Fluid f, CgFluidStack stack) {
        return new FluidStack(f, Math.max(1, stack.millibuckets()));
    }

    // A colour with no alpha is opaque: Forge's default white is 0xFFFFFFFF, a mod's may be 0x3F76E4
    static int opaque(int argb) {
        return (argb >>> 24) == 0 ? 0xFF000000 | argb : argb;
    }
}
