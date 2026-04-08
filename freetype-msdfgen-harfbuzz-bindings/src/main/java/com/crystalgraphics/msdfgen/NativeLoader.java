package com.crystalgraphics.msdfgen;

/**
 * Delegates to the shared NativeLoader in the freetype package.
 * FreeType, MSDFgen, and HarfBuzz are compiled into a single JNI library.
 */
public final class NativeLoader {

    private NativeLoader() {
    }

    public static synchronized void load() {
        com.crystalgraphics.freetype.NativeLoader.ensureLoaded();
    }

    public static boolean isLoaded() {
        return com.crystalgraphics.freetype.NativeLoader.isLoaded();
    }

    static String getOsName() {
        return com.crystalgraphics.freetype.NativeLoader.detectOS();
    }

    static String getArchName() {
        return com.crystalgraphics.freetype.NativeLoader.detectArch();
    }

    static String mapLibraryName(String name) {
        String os = getOsName();
        if ("windows".equals(os)) {
            return name + ".dll";
        } else if ("macos".equals(os)) {
            return "lib" + name + ".dylib";
        } else {
            return "lib" + name + ".so";
        }
    }
}
