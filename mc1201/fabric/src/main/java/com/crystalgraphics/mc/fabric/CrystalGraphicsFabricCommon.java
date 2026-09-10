package com.crystalgraphics.mc.fabric;

import com.crystalgraphics.mc.modern.platform.PlatformServiceModern;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.platform.CgPlatform;

import net.fabricmc.api.ModInitializer;

import org.apache.logging.log4j.LogManager;

/**
 * Both sides. The platform bundle is what every consumer reads through {@code CgPlatform}, and a
 * dedicated server needs it as much as a client does -- {@code register} asks for a GL backend by
 * trying, so the absence of LWJGL there is handled rather than fatal.
 *
 * <p>Separate from {@link CrystalGraphicsFabric} because Fabric runs no {@code client} entrypoint on
 * a server: registering there left {@code CgPlatform} unset for the whole server process, and every
 * accessor threw {@code IllegalStateException: CgPlatform not yet registered}. Found by CrystalGUI's
 * dedicated-server smoke check.</p>
 */
public final class CrystalGraphicsFabricCommon implements ModInitializer {
    
    @Override
    public void onInitialize() {
        // WHICH VARIANT, in the log rather than the crash report: Fabric Loader exposes no crash
        // callable, so unlike Forge and 1.7.10 there is nothing to register with. @see CrashVariant
        LogManager.getLogger("CrystalGraphics").info("[cg] {}: {}", CrashVariant.LABEL,
                CrashVariant.report(CrystalGraphicsFabricCommon.class));
        CgPlatform.register(PlatformServiceModern.getInstance());
    }
}
