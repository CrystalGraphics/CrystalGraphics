package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;

import java.util.logging.Logger;

final class HarnessDiagnostics {

    private static final Logger LOGGER = Logger.getLogger(HarnessDiagnostics.class.getName());

    static void logStartup(HarnessContext ctx) {
        LOGGER.info("=== CrystalGraphics Debug Harness ===");
        LOGGER.info("[Harness] GL Version:  " + ctx.getGlVersion());
        LOGGER.info("[Harness] GL Vendor:   " + ctx.getGlVendor());
        LOGGER.info("[Harness] GL Renderer: " + ctx.getGlRenderer());

        try {
            CgCapabilities caps = CgCapabilities.detectUncached();
            LOGGER.info("[Harness] Core FBO:    " + caps.isCoreFbo());
            LOGGER.info("[Harness] ARB FBO:     " + caps.isArbFbo());
            LOGGER.info("[Harness] EXT FBO:     " + caps.isExtFbo());
            LOGGER.info("[Harness] Core Shaders:" + caps.isCoreShaders());
            LOGGER.info("[Harness] VAO:         " + caps.isVaoSupported());
            LOGGER.info("[Harness] MapBufRange: " + caps.isMapBufferRangeSupported());
            LOGGER.info("[Harness] MaxTexSize:  " + caps.getMaxTextureSize());
            LOGGER.info("[Harness] MaxDrawBuf:  " + caps.getMaxDrawBuffers());
            LOGGER.info("[Harness] Preferred FBO backend: " + caps.preferredFboBackend());
        } catch (Exception e) {
            LOGGER.warning("[Harness] Could not detect CgCapabilities: " + e.getMessage());
        }

        String nativeLibPath = System.getProperty("java.library.path", "(not set)");
        LOGGER.info("[Harness] java.library.path: " + nativeLibPath);

        String ftNativePath = System.getProperty("freetype.harfbuzz.native.path", "(not set)");
        LOGGER.info("[Harness] freetype.harfbuzz.native.path: " + ftNativePath);
    }

    private HarnessDiagnostics() { }
}
