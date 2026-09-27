package com.crystalgraphics.mc.legacy;

import com.crystalgraphics.mc.legacy.platform.PlatformServiceLegacy;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.ICrashCallable;

/**
 * CrystalGraphics on Forge 1.8–1.12.2, both sides: the platform bundle and the crash-report line. No GL
 * work here — FML's splash screen loads mods on a context of its own, so the render context initialises
 * lazily from the first world pass.
 */
public final class CrystalGraphicsLegacy implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event -> {
            FMLCommonHandler.instance().registerCrashCallable(new ICrashCallable() {
                @Override public String getLabel() { return CrashVariant.LABEL; }
                @Override public String call() { return CrashVariant.report(CrystalGraphicsLegacy.class); }
            });
            PlatformServiceLegacy.register(FMLCommonHandler.instance().getSide().isClient());
        });
    }
}
