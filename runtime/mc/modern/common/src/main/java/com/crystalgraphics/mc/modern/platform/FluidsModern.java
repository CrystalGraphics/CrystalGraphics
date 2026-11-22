package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.fluid.CgFluidStack;

import net.minecraft.world.level.material.Fluid;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}
//? if >=26.1 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
*///?}

/**
 * What every loader's {@code CgFluids} host shares on the modern tree: a fluid by its registry name, and from 26.1 the
 * game's own fluid model, whose still sprite every loader reads the same way. Client.
 */
public final class FluidsModern {

    private FluidsModern() {
    }

    /** The fluid {@code stack} names; null where the registry has none. */
    public static Fluid fluid(CgFluidStack stack) {
        String id = stack.fluid();
        //? if >=1.21.3 {
        /*return BuiltInRegistries.FLUID.containsKey(ResourceIds.parse(id)) ? BuiltInRegistries.FLUID.getValue(ResourceIds.parse(id)) : null;
        *///?} elif >=1.19.3 {
        return BuiltInRegistries.FLUID.containsKey(ResourceIds.parse(id)) ? BuiltInRegistries.FLUID.get(ResourceIds.parse(id)) : null;
        //?} else {
        /*Fluid f = Registry.FLUID.get(ResourceIds.parse(id));   // as BlocksModern: the default's key is not the one asked
        return Registry.FLUID.getKey(f).equals(ResourceIds.parse(id)) ? f : null;
        *///?}
    }

    //? if >=26.1 {
    /*// The game's model for a fluid: its still, flowing and overlay sprites, and its tint
    public static FluidModel model(Fluid fluid) {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.defaultFluidState());
    }

    // The still sprite's name in the block atlas
    public static String stillSprite(Fluid fluid) {
        return String.valueOf(model(fluid).stillMaterial().sprite().contents().name());
    }
    *///?}

    /** A colour with no alpha is opaque: loaders give a tint as RGB, {@code 0x3F76E4} for water. */
    public static int opaque(int argb) {
        return (argb >>> 24) == 0 ? 0xFF000000 | argb : argb;
    }
}
