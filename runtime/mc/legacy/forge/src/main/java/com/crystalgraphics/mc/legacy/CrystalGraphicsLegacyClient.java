package com.crystalgraphics.mc.legacy;

import com.crystalgraphics.mc.legacy.platform.service.ReloadService;
import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;

/** CrystalGraphics on a Forge 1.8–1.12.2 client, after {@link CrystalGraphicsLegacy}: F3+T reloads its assets. */
public final class CrystalGraphicsLegacyClient implements VariantEntry {

    @Override
    public void start(Object context) {
        ((FmlEvents) context).on("FMLInitializationEvent", event -> ReloadService.attachToResourceManager());
    }
}
