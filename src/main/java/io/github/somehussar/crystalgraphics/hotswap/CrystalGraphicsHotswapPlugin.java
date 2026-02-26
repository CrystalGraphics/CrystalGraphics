package io.github.somehussar.crystalgraphics.hotswap;

import java.security.ProtectionDomain;

import org.hotswap.agent.annotation.LoadEvent;
import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.config.PluginManager;
import org.hotswap.agent.logging.AgentLogger;

@Plugin(
    name = "CrystalGraphicsHSA",
    description = "Runs LaunchWrapper transformers as needed for reloaded classes.",
    testedVersions = "1.7.10",
    expectedVersions = "1.7.10")
public class CrystalGraphicsHotswapPlugin {

    public static final String PLUGIN_PACKAGE = "io.github.somehussar.crystalgraphics.hotswap";
    private static final AgentLogger LOGGER = AgentLogger.getLogger(CrystalGraphicsHotswapPlugin.class);

    private static final boolean DISABLE = Boolean.getBoolean("crystalgraphics.hotswap.disable");
    private static final boolean VERBOSE = Boolean.getBoolean("crystalgraphics.hotswap.verbose");
    private static final boolean FULL_CHAIN = Boolean.getBoolean("crystalgraphics.hotswap.fullChain");

    static {
        LOGGER.info("CrystalGraphics REDEFINE hook installed (disable={}, verbose={}, fullChain={})",
            DISABLE, VERBOSE, FULL_CHAIN);
    }

    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public static byte[] reloadClass(byte[] inputClassBytes, ClassLoader classLoader, String className,
        ProtectionDomain protectionDomain) {
        if (DISABLE) {
            return inputClassBytes;
        }
        final Thread curThread = Thread.currentThread();
        final ClassLoader ctxLoader = curThread.getContextClassLoader();
        try {
            if (!classLoader.getClass()
                .getName()
                .equals("net.minecraft.launchwrapper.LaunchClassLoader")) {
                return inputClassBytes;
            }
            if (VERBOSE) {
                LOGGER.debug("Retransforming {}", className);
            }
            PluginManager.getInstance()
                .initClassLoader(classLoader, protectionDomain);
            curThread.setContextClassLoader(classLoader);
            final Class<?> helper = Class
                .forName("io.github.somehussar.crystalgraphics.hotswap.TransformHelper", true, classLoader);
            byte[] newClassBytes = (byte[]) helper
                .getMethod("transform", ClassLoader.class, String.class, byte[].class)
                .invoke(null, classLoader, className, inputClassBytes);
            return newClassBytes;
        } catch (Exception e) {
            LOGGER.error("Error when attempting to pass {} through LaunchClassLoader", e, className);
            return inputClassBytes;
        } finally {
            curThread.setContextClassLoader(ctxLoader);
        }
    }
}
