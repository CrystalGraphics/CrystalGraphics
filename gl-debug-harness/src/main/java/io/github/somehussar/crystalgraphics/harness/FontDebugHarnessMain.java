package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.*;
import io.github.somehussar.crystalgraphics.harness.util.HarnessDiagnostics;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

public final class FontDebugHarnessMain {

    private static final Logger LOGGER = Logger.getLogger(FontDebugHarnessMain.class.getName());

    public static void main(String[] args) {
        String mode = null;
        boolean listMode = false;
        boolean helpMode = false;

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length());
            } else if ("--list".equals(arg)) {
                listMode = true;
            } else if ("--help".equals(arg)) {
                helpMode = true;
            }
        }

        SceneRegistry registry = SceneRegistry.createDefault();

        if (helpMode) {
            printHelp(registry);
            return;
        }

        if (listMode) {
            printList(registry);
            return;
        }

        if (mode == null || mode.isEmpty()) {
            System.err.println("ERROR: No mode specified.");
            System.err.println();
            printHelp(registry);
            System.exit(1);
            return;
        }

        SceneRegistry.Entry entry = registry.lookup(mode);
        if (entry == null) {
            System.err.println("ERROR: Unknown mode '" + mode + "'");
            System.err.println();
            System.err.println("Valid modes:");
            printList(registry);
            System.exit(1);
            return;
        }

        LOGGER.info("[Harness] Selected mode: " + mode);

        // Build the typed config (may be TextSceneConfig, AtlasDumpConfig, or base HarnessConfig)
        // from defaults → system properties → CLI args. This is the single resolution point;
        // scenes read the resolved config from HarnessContext.getSceneConfig() rather than
        // re-parsing raw CLI args from a global static.
        HarnessConfig config = createConfig(mode, args);
        HarnessOutputDir.ensureExists(config.getOutputDir());

        // Create scene-specific subdirectory: harness-output/{sceneName}/
        // All scene outputs go into their own subdirectory for organization.
        String sceneOutputDir = config.getOutputDir() + File.separator + mode;
        HarnessOutputDir.ensureExists(sceneOutputDir);
        LOGGER.info("[Harness] Output directory: " + sceneOutputDir);

        // Resolve the output name prefix: --output-name overrides the default (scene name)
        String outputName = config.getOutputName() != null ? config.getOutputName() : mode;
        LOGGER.info("[Harness] Output name prefix: " + outputName);

        HarnessContext ctx = null;
        boolean shouldShutdown = true;
        try {
            ctx = HarnessContext.create(config.getWidth(), config.getHeight());
            HarnessDiagnostics.logStartup(ctx);

            // Populate context with all configuration — single source of truth
            ctx.setOutputSettings(new OutputSettings(sceneOutputDir, outputName));
            ctx.setSceneConfig(config);

            // Resolve world settings once from WorldConfig defaults and freeze
            // them for the entire run. No rendering code should call
            // WorldConfig.get() after this point.
            ctx.setWorldSettings(WorldSettings.resolveFromDefaults());

            HarnessSceneLifecycle scene = entry.getFactory().create();

            boolean isInteractiveMode =
                    entry.getDescriptor().getLifecycleMode() == SceneDescriptor.LifecycleMode.INTERACTIVE;

            if (isInteractiveMode) {
                if (!(scene instanceof InteractiveSceneLifecycle)) {
                    throw new IllegalStateException(
                            "Mode '" + mode + "' is INTERACTIVE but scene does not implement InteractiveSceneLifecycle: "
                                    + scene.getClass().getName());
                }
                InteractiveSceneLifecycle interactive = (InteractiveSceneLifecycle) scene;
                InteractiveSceneRunner runner = new InteractiveSceneRunner(interactive, ctx);
                runner.run();
                shouldShutdown = runner.shouldShutdown();
            } else {
                scene.init(ctx);
                try {
                    scene.render(ctx, FrameInfo.SINGLE_FRAME);
                } finally {
                    scene.dispose();
                }
            }

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

        if (!shouldShutdown) {
            LOGGER.info("[Harness] Scene requested no shutdown — returning.");
        }
    }

    static HarnessConfig createConfigForMode(String mode, String[] args) {
        return createConfig(mode, args);
    }

    private static HarnessConfig createConfig(String mode, String[] args) {
        HarnessConfig config;
        if ("atlas-dump".equals(mode)) {
            config = AtlasDumpConfig.create(args);
        } else if ("text-scene".equals(mode)
                || "world-text-scene".equals(mode)
                || "world-text-3d".equals(mode)) {
            // All text-related scenes share TextSceneConfig for font-size-px,
            // text content, and other text-rendering parameters.
            config = TextSceneConfig.create(args);
        } else {
            config = new HarnessConfig();
            config.applySystemProperties();
            config.applyCliArgs(HarnessConfig.parseCliArgs(args));
        }
        // Ensure --output-name is always propagated regardless of config type
        java.util.Map<String, String> parsedArgs = HarnessConfig.parseCliArgs(args);
        if (parsedArgs.containsKey("output-name") && config.getOutputName() == null) {
            config.setOutputName(parsedArgs.get("output-name"));
        }
        return config;
    }

    private static void printHelp(SceneRegistry registry) {
        System.out.println("CrystalGraphics Debug Harness");
        System.out.println();
        System.out.println("Usage: --mode=<mode> [options]");
        System.out.println("       --list          List all available modes");
        System.out.println("       --help          Show this help");
        System.out.println();
        System.out.println("Common options:");
        System.out.println("  --output-dir=<dir>     Output directory (default: gl-debug-harness/harness-output)");
        System.out.println("  --output-name=<prefix> Custom filename prefix for outputs (default: scene name)");
        System.out.println("  --font-path=<path>     Font file path (default: system font)");
        System.out.println("  --width=<n>            Width in pixels (default: 800)");
        System.out.println("  --height=<n>           Height in pixels (default: 600)");
        System.out.println();
        System.out.println("Text-scene options:");
        System.out.println("  --pose-scale=<f>                 PoseStack scale factor (default: 1.0)");
        System.out.println("  --scales=<f,f,...>               Comma-separated scales for multi-scale comparison");
        System.out.println("  --output-filename=<name.png>     Custom output filename (default: text-scene.png)");
        System.out.println("  --text=<string>                  Test string");
        System.out.println("  --font-size-px=<n>               Font size in px (default: 32)");
        System.out.println("  --gui-scale=<n>                  Simulated MC GUI scale factor (default: 1)");
        System.out.println();
        System.out.println("Atlas options (atlas-dump mode):");
        System.out.println("  --atlas-type=<bitmap|msdf|both>  Atlas type (default: both)");
        System.out.println("  --atlas-size=<n>                 Atlas texture size (default: 512)");
        System.out.println("  --bitmap-px-size=<n>             Bitmap atlas font size in px (default: 24)");
        System.out.println("  --msdf-px-size=<n>               MSDF atlas font size in px (default: 32, min: 32)");
        System.out.println("  --msdf-atlas-scale=<n>           MSDF atlas generation scale in px/EM (default: 48)");
        System.out.println("  --msdf-px-range=<f>              MSDF pixel range (default: 4.0)");
        System.out.println("  --font-size-px=<n>               Shared font size (overrides both bitmap/msdf)");
        System.out.println("  --text=<string>                  Test string");
        System.out.println();
        System.out.println("Parity / overflow options (atlas-dump mode):");
        System.out.println("  --parity-prewarm=<true|false>    Deterministic MSDF prewarm for parity (default: false)");
        System.out.println("  --prewarm-bitmap=<true|false>    Deterministic bitmap prewarm (default: false)");
        System.out.println("  --dump-all-pages=<true|false>    Dump all atlas pages, not just the first (default: false)");
        System.out.println("  --atlas-page-size=<n>            Override per-page atlas size (default: auto)");
        System.out.println("  --verify-msdf=<true|false>       Run CPU-side MSDF reconstruction verification (default: false)");
        System.out.println("  --msdf-verify-reference-px=<n>   Reference grayscale render size in px (default: 48)");
        System.out.println("  --msdf-verify-reconstruction-threshold=<f>  Reconstruction alpha threshold in [0,1] (default: 0.5)");
        System.out.println("  --msdf-verify-reference-threshold=<f>       Reference alpha threshold in [0,1] (default: 0.5)");
        System.out.println("  --msdf-verify-max-mismatch-ratio=<f>        Maximum allowed mismatch ratio in [0,1] (default: 0.02)");
        System.out.println("  --msdf-verify-dump-passing=<true|false>     Dump artifacts for passing glyphs too (default: false)");
        System.out.println("  --msdf-overlap-support=<true|false>         Enable overlap support during MSDF generation (default: true)");
        System.out.println("  --msdf-error-correction-mode=<n>            Error correction mode integer (default: 0)");
        System.out.println("  --msdf-distance-check-mode=<n>              Distance check mode integer (default: 0)");
        System.out.println("  --msdf-min-deviation-ratio=<f>              Minimum deviation ratio (default: 1.111111...)");
        System.out.println("  --msdf-min-improve-ratio=<f>                Minimum improvement ratio (default: 1.111111...)");
        System.out.println("  --msdf-spacing-px=<n>                       Atlas spacing in pixels (default: 1)");
        System.out.println("  --msdf-miter-limit=<f>                      Miter limit for layout bounds (default: 1.0)");
        System.out.println("  --msdf-align-origin-x=<true|false>          Pixel-align origin on X (default: false)");
        System.out.println("  --msdf-align-origin-y=<true|false>          Pixel-align origin on Y (default: true)");
        System.out.println("  --msdf-edge-coloring-mode=<simple|ink_trap|distance>  Edge coloring strategy (default: simple)");
        System.out.println("  --msdf-edge-coloring-angle=<f>              Edge coloring angle threshold (default: 3.0)");
        System.out.println();
        System.out.println("Available modes:");
        printList(registry);
    }

    private static void printList(SceneRegistry registry) {
        List<SceneRegistry.Entry> entries = registry.allEntries();
        int maxIdLen = 0;
        for (SceneRegistry.Entry e : entries) {
            maxIdLen = Math.max(maxIdLen, e.getDescriptor().getId().length());
        }

        System.out.println();
        System.out.println("Rendering Scenes:");
        for (SceneRegistry.Entry e : entries) {
            SceneDescriptor d = e.getDescriptor();
            if (d.getCategory() == SceneDescriptor.Category.SCENE) {
                System.out.printf("  %-" + (maxIdLen + 2) + "s %s [%s]%n",
                        d.getId(), d.getDescription(), d.getLifecycleMode());
            }
        }

        System.out.println();
        System.out.println("Diagnostic Tools:");
        for (SceneRegistry.Entry e : entries) {
            SceneDescriptor d = e.getDescriptor();
            if (d.getCategory() == SceneDescriptor.Category.DIAGNOSTIC_TOOL) {
                System.out.printf("  %-" + (maxIdLen + 2) + "s %s%n",
                        d.getId(), d.getDescription());
            }
        }
        System.out.println();
    }
}
