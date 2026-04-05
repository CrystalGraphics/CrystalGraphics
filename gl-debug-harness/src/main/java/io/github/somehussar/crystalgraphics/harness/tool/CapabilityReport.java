package io.github.somehussar.crystalgraphics.harness.tool;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.harness.config.HarnessConfig;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.HarnessScene;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;

/**
 * Agent-debug tool #5: Write a capability summary report using CgCapabilities.
 *
 * <p>Registered as diagnostic mode "capability-report".</p>
 */
public final class CapabilityReport implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(CapabilityReport.class.getName());

    @Override
    public void run(HarnessContext ctx) {
        run(ctx, ctx.getOutputDir(), null);
    }

    void run(HarnessContext ctx, String outputDir, HarnessConfig config) {
        String filename = "capability-report.txt";
        File outFile = new File(outputDir, filename);

        try {
            PrintWriter pw = new PrintWriter(new FileWriter(outFile));
            try {
                pw.println("=== CrystalGraphics Capability Report ===");
                pw.println("Timestamp: " + System.currentTimeMillis());
                pw.println();

                pw.println("-- GL Context --");
                pw.println("GL_VERSION:  " + ctx.getGlVersion());
                pw.println("GL_VENDOR:   " + ctx.getGlVendor());
                pw.println("GL_RENDERER: " + ctx.getGlRenderer());
                pw.println();

                pw.println("-- CgCapabilities --");
                try {
                    CgCapabilities caps = CgCapabilities.detectUncached();
                    pw.println("Core FBO:          " + caps.isCoreFbo());
                    pw.println("ARB FBO:           " + caps.isArbFbo());
                    pw.println("EXT FBO:           " + caps.isExtFbo());
                    pw.println("Core Shaders:      " + caps.isCoreShaders());
                    pw.println("VAO:               " + caps.isVaoSupported());
                    pw.println("MapBufferRange:    " + caps.isMapBufferRangeSupported());
                    pw.println("Max Texture Size:  " + caps.getMaxTextureSize());
                    pw.println("Max Draw Buffers:  " + caps.getMaxDrawBuffers());
                    pw.println("Preferred FBO:     " + caps.preferredFboBackend());
                } catch (Exception e) {
                    pw.println("ERROR: " + e.getMessage());
                }
                pw.println();

                pw.println("-- Harness Environment --");
                pw.println("java.version:      " + System.getProperty("java.version"));
                pw.println("java.vendor:       " + System.getProperty("java.vendor"));
                pw.println("os.name:           " + System.getProperty("os.name"));
                pw.println("os.arch:           " + System.getProperty("os.arch"));
                pw.println("java.library.path: " + System.getProperty("java.library.path", "(not set)"));
                pw.println("native path:       " + System.getProperty("freetype.harfbuzz.native.path", "(not set)"));
                pw.println();

                pw.println("=== End Capability Report ===");
            } finally {
                pw.close();
            }

            LOGGER.info("[CapabilityReport] Wrote report to " + outFile.getAbsolutePath()
                    + " (" + outFile.length() + " bytes)");

        } catch (IOException e) {
            throw new RuntimeException("Failed to write capability report: " + outFile.getAbsolutePath(), e);
        }
    }
}
