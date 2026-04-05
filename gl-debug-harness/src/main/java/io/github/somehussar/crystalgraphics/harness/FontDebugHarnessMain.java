package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.*;
import io.github.somehussar.crystalgraphics.harness.util.HarnessDiagnostics;
import io.github.somehussar.crystalgraphics.harness.util.HarnessOutputDir;

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

        HarnessConfig config = createConfig(mode, args);
        HarnessConfig.setGlobalCliArgs(args);
        HarnessOutputDir.ensureExists(config.getOutputDir());
        LOGGER.info("[Harness] Output directory: " + config.getOutputDir());

        HarnessContext ctx = null;
        try {
            ctx = HarnessContext.create();
            HarnessDiagnostics.logStartup(ctx);

            HarnessScene scene = entry.getFactory().create();
            scene.run(ctx, config.getOutputDir());

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

    static HarnessConfig createConfigForMode(String mode, String[] args) {
        return createConfig(mode, args);
    }

    private static HarnessConfig createConfig(String mode, String[] args) {
        HarnessConfig config;
        if ("atlas-dump".equals(mode)) {
            config = AtlasDumpConfig.create(args);
        } else if ("text-scene".equals(mode)) {
            config = TextSceneConfig.create(args);
        } else {
            config = new HarnessConfig();
            config.applySystemProperties();
            config.applyCliArgs(HarnessConfig.parseCliArgs(args));
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
        System.out.println("  --font-path=<path>     Font file path (default: system font)");
        System.out.println("  --width=<n>            Width in pixels (default: 800)");
        System.out.println("  --height=<n>           Height in pixels (default: 600)");
        System.out.println();
        System.out.println("Atlas options (atlas-dump mode):");
        System.out.println("  --atlas-type=<bitmap|msdf|both>  Atlas type (default: both)");
        System.out.println("  --atlas-size=<n>                 Atlas texture size (default: 512)");
        System.out.println("  --bitmap-px-size=<n>             Bitmap atlas font size in px (default: 24)");
        System.out.println("  --msdf-px-size=<n>               MSDF atlas font size in px (default: 32, min: 32)");
        System.out.println("  --font-size-px=<n>               Shared font size (overrides both bitmap/msdf)");
        System.out.println("  --text=<string>                  Test string");
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
