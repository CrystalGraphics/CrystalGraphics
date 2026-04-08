package com.crystalgraphics.harfbuzz;

/**
 * Delegates to the shared NativeLoader in the freetype package.
 * FreeType, MSDFgen, and HarfBuzz are compiled into a single JNI library.
 */
public final class NativeLoader {
    private NativeLoader() {
    }

    public static void ensureLoaded() {
        com.crystalgraphics.freetype.NativeLoader.ensureLoaded();
    }
}
