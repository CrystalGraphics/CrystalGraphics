package com.crystalgraphics;

import com.crystalgraphics.platform.PlatformService1710;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main Forge mod container for CrystalGraphics.
 *
 * <p>CrystalGraphics ships its Minecraft-facing functionality via Mixins, but Forge still expects a normal
 * {@link Mod} container when other mods declare dependencies on {@code modid=crystalgraphics}. This class
 * provides that container and a central place for lifecycle logging.</p>
 *
 * <p>This class intentionally performs no OpenGL work and does not assume a current GL context. Rendering
 * lives under {@code com.crystalgraphics.gl}.</p>
 *
 * <p>There is <strong>no coremod</strong> any more. The ASM transformer that used to redirect GL call sites
 * process-wide was deleted along with the state mirror it fed: GL state now comes from
 * {@code AngelicaStateProvider}, which reads Angelica's own mirror, and falls back to {@code glGet}.</p>
 */
@Mod(
    modid = CrystalGraphics.MODID,
    name = CrystalGraphics.NAME,
    version = CrystalGraphics.VERSION,
    acceptableRemoteVersions = "*"
)
public final class CrystalGraphics{

    /** The mod ID used for Forge dependency resolution. */
    public static final String MODID = "crystalgraphics";

    /** Human-readable mod name. */
    public static final String NAME = "CrystalGraphics";

    /** Mod version string (kept in sync with gradle.properties). */
    public static final String VERSION = "1.0.0";

    /** Logger for mod lifecycle messages. */
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    /**
     * Forge pre-initialization hook.
     *
     * @param event the pre-initialization event
     */
    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        LOGGER.info("{}: preInit (mixins should already be active)", NAME);
        PlatformService1710.onPreInit();
    }

    /**
     * Forge initialization hook.
     *
     * @param event the initialization event
     */
    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
        PlatformService1710.onInit();
        
        // Aggregate validation of all mod OpenGL requirements registered during pre-init.
        // On dedicated server this is a no-op (returns immediately).
        // On client, throws a CustomModLoadingErrorDisplayException if any mod's
        // minimum OpenGL requirement exceeds the detected driver version.
        CrystalGraphicsVersion.processAllRequirements();
    }

    /**
     * Forge post-initialization hook.
     *
     * @param event the post-initialization event
     */
    @Mod.EventHandler
    public void onPostInit(FMLPostInitializationEvent event) {
        LOGGER.info("{}: postInit", NAME);
    }

}
