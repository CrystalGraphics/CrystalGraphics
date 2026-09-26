package com.crystalgraphics.mc.legacy;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * CrystalGraphics' variant on Forge 1.8 to 1.12.2, both sides. The platform services arrive with the
 * legacy host; until then it announces which node the bootstrapper chose.
 */
public final class CrystalGraphicsLegacy implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event -> ((FMLPreInitializationEvent) event).getModLog()
                .info("CrystalGraphics legacy variant on Minecraft " + Loader.instance().getMCVersionString()));
    }
}
