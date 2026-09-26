package com.crystalgraphics.mc.legacy;

import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/** CrystalGraphics' variant on a Forge 1.8 to 1.12.2 client, after {@link CrystalGraphicsLegacy}. */
public final class CrystalGraphicsLegacyClient implements VariantEntry {

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLInitializationEvent", event -> System.out.println("[CrystalGraphics] legacy client, "
                + Minecraft.getMinecraft().displayWidth + "x" + Minecraft.getMinecraft().displayHeight));
    }
}
