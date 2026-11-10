package com.crystalgraphics.mc.legacy;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.legacy.platform.CommandLegacy;
import com.crystalgraphics.mc.legacy.platform.PlatformServiceLegacy;
import com.crystalgraphics.mc.legacy.platform.net.NetworkLegacy;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.ICrashCallable;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/**
 * CrystalGraphics on Forge 1.8–1.12.2, both sides: the platform bundle, the crash-report line and the
 * connections ({@link NetworkLegacy}). No GL work here — FML's splash screen loads mods on a context of its
 * own, so the engine starts at the first frame's end, the title screen's (`CgRenderHook`).
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
            PlatformServiceLegacy.install(FMLCommonHandler.instance().getSide().isClient());
            CgEngineLifecycle.preInit();
        });
        events.on("FMLInitializationEvent", event -> NetworkLegacy.install());
        events.on("FMLServerStartingEvent", event -> {
            CgEngineLifecycle.serverStarting(null);
            ((FMLServerStartingEvent) event).registerServerCommand(new CommandLegacy("cg", false));
        });
        events.on("FMLServerStoppingEvent", event -> CgEngineLifecycle.serverStopping());
    }
}
