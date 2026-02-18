package io.github.somehussar.crystalgraphics;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main Forge mod container for CrystalGraphics.
 *
 * <p>CrystalGraphics primarily ships functionality via an always-on coremod
 * transformer and Mixins, but Forge still expects a normal {@link Mod}
 * container when other mods declare dependencies on {@code modid=crystalgraphics}.
 * This class provides that container and a central place for lifecycle logging.</p>
 *
 * <p>This class intentionally performs no OpenGL work and does not assume
 * a current GL context. All rendering and interception logic lives under
 * {@code io.github.somehussar.crystalgraphics.mc.coremod} and
 * {@code io.github.somehussar.crystalgraphics.gl}.</p>
 */
@Mod(
    modid = CrystalGraphics.MODID,
    name = CrystalGraphics.NAME,
    version = CrystalGraphics.VERSION,
    acceptableRemoteVersions = "*"
)
public final class CrystalGraphics {

    /** The mod ID used for Forge dependency resolution. */
    public static final String MODID = "crystalgraphics";

    /** Human-readable mod name. */
    public static final String NAME = "CrystalGraphics";

    /** Mod version string (kept in sync with gradle.properties). */
    public static final String VERSION = "1.0.0";

    /** Logger for mod lifecycle messages. */
    private static final Logger LOGGER = LogManager.getLogger(NAME);

    /**
     * Forge pre-initialization hook.
     *
     * @param event the pre-initialization event
     */
    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        LOGGER.info("{}: preInit (coremod + mixins should already be active)", NAME);
    }

    /**
     * Forge initialization hook.
     *
     * @param event the initialization event
     */
    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        LOGGER.info("{}: init", NAME);
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
