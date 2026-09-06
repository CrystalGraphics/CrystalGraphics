package com.crystalgraphics.mc.fabric;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client only. The platform bundle is registered by {@link CrystalGraphics1201FabricCommon}, which runs
 * on both sides and runs first -- Fabric drains {@code main} entrypoints before {@code client} ones.
 */
public final class CrystalGraphics1201Fabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CgEngineFabricEvents.register();
    }
}
