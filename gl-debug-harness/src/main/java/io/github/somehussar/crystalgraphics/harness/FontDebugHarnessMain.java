package io.github.somehussar.crystalgraphics.harness;

import java.util.logging.Logger;

/**
 * Standalone LWJGL 2 / OpenGL 3.0 debug harness for CrystalGraphics.
 *
 * <p>Runs outside Minecraft to debug font rendering, FBO pipelines, and
 * atlas generation in isolation. Produces deterministic PNG artifacts under
 * {@code build/harness-output/}.</p>
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li>{@code --mode=triangle}: Hello triangle → {@code triangle.png}</li>
 *   <li>{@code --mode=fbo-triangle}: FBO-rendered triangle → {@code fbo-triangle.png}</li>
 *   <li>{@code --mode=atlas-dump}: Bitmap atlas dump → {@code atlas-dump.png}</li>
 *   <li>{@code --mode=text-scene}: Full text scene → {@code text-scene.png} + {@code bitmap-atlas.png}</li>
 *   <li>{@code --mode=renderer-parity}: CgTextRenderer parity scene → {@code renderer-parity.png} + atlas dumps</li>
 * </ul>
 */
public final class FontDebugHarnessMain {

    private static final Logger LOGGER = Logger.getLogger(FontDebugHarnessMain.class.getName());

    public static void main(String[] args) {
        String mode = null;
        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length());
            }
        }

        if (mode == null || mode.isEmpty()) {
            System.err.println("Usage: --mode=<triangle|fbo-triangle|atlas-dump|text-scene|renderer-parity>");
            System.err.println("  triangle     — Render hello triangle to triangle.png");
            System.err.println("  fbo-triangle — Render hello triangle via FBO to fbo-triangle.png");
            System.err.println("  atlas-dump   — Generate bitmap glyph atlas to atlas-dump.png");
            System.err.println("  text-scene   — Full text scene to text-scene.png + bitmap-atlas.png");
            System.err.println("  renderer-parity — CgTextRenderer pipeline parity to renderer-parity.png");
            System.exit(1);
            return;
        }

        if (!isValidMode(mode)) {
            System.err.println("ERROR: Unknown mode '" + mode + "'");
            System.err.println("Valid modes: triangle, fbo-triangle, atlas-dump, text-scene, renderer-parity");
            System.exit(1);
            return;
        }

        LOGGER.info("[Harness] Selected mode: " + mode);

        String outputDir = System.getProperty("harness.output.dir",
                "gl-debug-harness/build/harness-output");
        HarnessOutputDir.ensureExists(outputDir);
        LOGGER.info("[Harness] Output directory: " + outputDir);

        HarnessContext ctx = null;
        try {
            ctx = HarnessContext.create();
            HarnessDiagnostics.logStartup(ctx);

            HarnessScene scene = resolveScene(mode);
            scene.run(ctx, outputDir);

            LOGGER.info("[Harness] Mode '" + mode + "' completed successfully.");
        } catch (Exception e) {
            System.err.println("ERROR: Harness failed in mode '" + mode + "': " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        } finally {
            if (ctx != null) {
                ctx.destroy();
            }
            LOGGER.info("[Harness] Shutdown complete.");
        }
    }

    private static boolean isValidMode(String mode) {
        return "triangle".equals(mode)
                || "fbo-triangle".equals(mode)
                || "atlas-dump".equals(mode)
                || "text-scene".equals(mode)
                || "renderer-parity".equals(mode);
    }

    private static HarnessScene resolveScene(String mode) {
        if ("triangle".equals(mode)) {
            return new TriangleScene();
        }
        if ("fbo-triangle".equals(mode)) {
            return new FboTriangleScene();
        }
        if ("atlas-dump".equals(mode)) {
            return new AtlasDumpScene();
        }
        if ("text-scene".equals(mode)) {
            return new TextSceneScene();
        }
        if ("renderer-parity".equals(mode)) {
            return new CgRendererParityScene();
        }
        throw new IllegalArgumentException("Unknown mode: " + mode);
    }
}
