package io.github.somehussar.crystalgraphics.hotswap;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper functions that run within the app classloader domain.
 * Called reflectively from {@link CrystalGraphicsHotswapPlugin} so that
 * LaunchClassLoader types are resolved against the correct classloader.
 */
public class TransformHelper {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics-Hotswap");

    private static final String CG_TRANSFORMER_CLASS =
        "io.github.somehussar.crystalgraphics.mc.coremod.CrystalGraphicsTransformer";

    private static final boolean VERBOSE = Boolean.getBoolean("crystalgraphics.hotswap.verbose");
    private static final boolean FULL_CHAIN = Boolean.getBoolean("crystalgraphics.hotswap.fullChain");

    // Cached reflection fields
    private static Field classLoaderExceptionsField;
    private static Field transformerExceptionsField;

    public static byte[] transform(ClassLoader loader, String name, byte[] classBytes) {
        try {
            return transformInternal(loader, name, classBytes);
        } catch (Exception e) {
            LOGGER.error("Error transforming {} during hotswap", name, e);
            if (FULL_CHAIN) {
                try {
                    LOGGER.warn("fullChain failed for {}, falling back to CrystalGraphics-only transform", name);
                    return transformCgOnly((LaunchClassLoader) loader, name, classBytes);
                } catch (Exception fallbackException) {
                    LOGGER.error("Fallback CrystalGraphics-only transform failed for {}", name, fallbackException);
                }
            }
            return classBytes;
        }
    }

    @SuppressWarnings("unchecked")
    private static byte[] transformInternal(ClassLoader loader, String name, byte[] classBytes) throws Exception {
        LaunchClassLoader lcl = (LaunchClassLoader) loader;

        // Cache classLoaderExceptions field
        if (classLoaderExceptionsField == null) {
            classLoaderExceptionsField = lcl.getClass().getDeclaredField("classLoaderExceptions");
            classLoaderExceptionsField.setAccessible(true);
        }
        // Cache transformerExceptions field
        if (transformerExceptionsField == null) {
            transformerExceptionsField = lcl.getClass().getDeclaredField("transformerExceptions");
            transformerExceptionsField.setAccessible(true);
        }

        Set<String> classLoaderExceptions = (Set<String>) classLoaderExceptionsField.get(lcl);
        Set<String> transformerExceptions = (Set<String>) transformerExceptionsField.get(lcl);

        // Skip classes matching classLoader exclusion prefixes
        for (String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                if (VERBOSE) {
                    LOGGER.debug("Skipping {} - matches classLoaderException prefix: {}", name, exception);
                }
                return classBytes;
            }
        }
        // Skip classes matching transformer exclusion prefixes
        for (String exception : transformerExceptions) {
            if (name.startsWith(exception)) {
                if (VERBOSE) {
                    LOGGER.debug("Skipping {} - matches transformerException prefix: {}", name, exception);
                }
                return classBytes;
            }
        }

        if (FULL_CHAIN) {
            // Apply the full transformer chain in order
            if (VERBOSE) {
                LOGGER.debug("Running full transformer chain on {}", name);
            }
            List<IClassTransformer> transformers = lcl.getTransformers();
            for (IClassTransformer xformer : transformers) {
                classBytes = xformer.transform(name, name, classBytes);
            }
        } else {
            classBytes = transformCgOnly(lcl, name, classBytes);
        }

        return classBytes;
    }

    private static byte[] transformCgOnly(LaunchClassLoader lcl, String name, byte[] classBytes) throws Exception {
        if (VERBOSE) {
            LOGGER.debug("Running CrystalGraphicsTransformer only on {}", name);
        }

        IClassTransformer cgTransformer = null;

        List<IClassTransformer> transformers = lcl.getTransformers();
        for (IClassTransformer xformer : transformers) {
            if (CG_TRANSFORMER_CLASS.equals(xformer.getClass().getName())) {
                cgTransformer = xformer;
                break;
            }
        }

        if (cgTransformer == null) {
            LOGGER.warn("CrystalGraphicsTransformer not found in transformer chain, falling back to reflective instantiation");
            Class<?> xformerClass = Class.forName(CG_TRANSFORMER_CLASS, true, lcl);
            cgTransformer = (IClassTransformer) xformerClass.newInstance();
        }

        return cgTransformer.transform(name, name, classBytes);
    }
}
