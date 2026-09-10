package com.crystalgraphics.hotswap;

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
 *
 * <p>Re-applies the LaunchWrapper transformer chain to hot-swapped class bytes. HotswapAgent hands us the
 * <em>untransformed</em> bytes on redefine, so without this a reloaded class loses every transform it had
 * when originally loaded — most importantly its Mixins.</p>
 *
 * <p><strong>Runs the whole chain, and no longer has a CrystalGraphics-only mode.</strong> That mode existed
 * to re-apply the GL redirect coremod, which is deleted — its state mirror was superseded by
 * {@code AngelicaStateProvider}, which reads Angelica's far more complete mirror. With no transformer of our
 * own left there is nothing a "CrystalGraphics-only" pass could apply, so the flag that selected it
 * ({@code -Dcrystalgraphics.hotswap.fullChain}) is gone and full-chain is unconditional.</p>
 */
public class TransformHelper {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics-Hotswap");

    private static final boolean VERBOSE = Boolean.getBoolean("crystalgraphics.hotswap.verbose");

    // Cached reflection fields
    private static Field classLoaderExceptionsField;
    private static Field transformerExceptionsField;

    public static byte[] transform(ClassLoader loader, String name, byte[] classBytes) {
        try {
            return transformInternal(loader, name, classBytes);
        } catch (Exception e) {
            // Returning the input unchanged degrades to "this class lost its transforms" rather than
            // failing the redefine outright, which is the right trade for a dev-only convenience.
            LOGGER.error("Error transforming {} during hotswap; using untransformed bytes", name, e);
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

        // Apply the full transformer chain in order.
        if (VERBOSE) {
            LOGGER.debug("Running full transformer chain on {}", name);
        }
        List<IClassTransformer> transformers = lcl.getTransformers();
        for (IClassTransformer xformer : transformers) {
            classBytes = xformer.transform(name, name, classBytes);
        }

        return classBytes;
    }

}
