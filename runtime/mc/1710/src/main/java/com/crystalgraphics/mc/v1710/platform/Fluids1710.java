package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.fluid.CgFluids;

import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * {@link CgFluids} on 1.7.10, client: Forge's {@code FluidRegistry}, its name Forge's own ({@code water}). The still
 * sprite is the icon's name in the block atlas ({@code water_still}), as 1.7.10 names its sprites.
 */
final class Fluids1710 implements CgFluids.Host {

    @Override
    public String name(CgFluidStack stack) {
        Fluid f = fluid(stack);
        return f == null ? null : f.getLocalizedName(new FluidStack(f, Math.max(1, stack.millibuckets())));
    }

    @Override
    public String stillSprite(CgFluidStack stack) {
        Fluid f = fluid(stack);
        IIcon icon = f == null ? null : f.getStillIcon();
        return icon == null ? null : icon.getIconName();
    }

    @Override
    public int tint(CgFluidStack stack) {
        Fluid f = fluid(stack);
        if (f == null) return 0xFFFFFFFF;
        int c = f.getColor(new FluidStack(f, Math.max(1, stack.millibuckets())));
        return (c >>> 24) == 0 ? 0xFF000000 | c : c;
    }

    private static Fluid fluid(CgFluidStack stack) {
        return FluidRegistry.getFluid(stack.fluid().replaceFirst("^minecraft:", ""));
    }
}
