package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.shared.FmlVersion;
import com.crystalgraphics.mc.shared.VariantBootstrap;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;

import static com.crystalgraphics.mc.modern.platform.CrystalGraphics.MODID;

/**
 * The one {@code @Mod} class the jar carries for Forge, whatever Minecraft version it runs on.
 *
 * <p>Forge's scanner reads every class in the jar, so two variants both bearing
 * {@code @Mod("crystalgraphics")} are two mods of one id and it refuses to load rather than choosing.
 * The variants carry none; this reads {@code variants.json} and constructs the one whose range covers
 * the running version.</p>
 */
@Mod(MODID)
public final class ForgeBootstrap {

    public ForgeBootstrap() {
        String minecraft = FmlVersion.of(FMLLoader.class);
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
        VariantBootstrap.startCommon(ForgeBootstrap.class, MODID, "forge", minecraft, context);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            VariantBootstrap.startClient(ForgeBootstrap.class, MODID, "forge", minecraft, context);
        }
    }
}
