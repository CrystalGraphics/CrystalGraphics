package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.mc.platform.PlatformService1201;
import com.crystalgraphics.platform.CgPlatform;

import net.fabricmc.api.ModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgraphics.mc.platform.CrystalGraphics1201.NAME;

/**
 * Both sides. The platform bundle is what every consumer reads through {@code CgPlatform}, and a
 * dedicated server needs it as much as a client does -- {@code register} asks for a GL backend by
 * trying, so the absence of LWJGL there is handled rather than fatal.
 *
 * <p>Separate from {@link CrystalGraphics1201Fabric} because Fabric runs no {@code client} entrypoint on
 * a server: registering there left {@code CgPlatform} unset for the whole server process, and every
 * accessor threw {@code IllegalStateException: CgPlatform not yet registered}. Found by CrystalGUI's
 * dedicated-server smoke check.</p>
 */
public final class CrystalGraphics1201FabricCommon implements ModInitializer {
    
    @Override
    public void onInitialize() {
        CgPlatform.register(PlatformService1201.getInstance());
    }
}
