package com.crystalgraphics.mc.modern.neoforge;

import com.crystalgraphics.mc.shared.FmlVersion;
import com.crystalgraphics.mc.shared.VariantBootstrap;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import com.crystalgraphics.mc.shared.FmlSide;
import net.neoforged.fml.loading.FMLLoader;

import static com.crystalgraphics.mc.modern.platform.CrystalGraphics.MODID;

/**
 * The one {@code @Mod} class the jar carries for NeoForge, whatever Minecraft version it runs on.
 *
 * <p>Same rule as the Forge one: the variants carry no annotation, because two of them bearing
 * {@code @Mod("crystalgraphics")} are two mods of one id to the scanner. The event bus NeoForge hands
 * this constructor is passed straight through as the entry's {@code context}.</p>
 */
@Mod(MODID)
public final class NeoForgeBootstrap {

    public NeoForgeBootstrap(IEventBus modBus) {
        String minecraft = FmlVersion.of(FMLLoader.class);
        VariantBootstrap.startCommon(NeoForgeBootstrap.class, MODID, "neoforge", minecraft, modBus);
        if (FmlSide.isClient(FMLLoader.class)) {
            VariantBootstrap.startClient(NeoForgeBootstrap.class, MODID, "neoforge", minecraft, modBus);
        }
    }
}
