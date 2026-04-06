package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Base configuration for all harness scenes.
 *
 * <p>Precedence: hardcoded defaults → system properties → CLI args.
 * Scene-specific configs extend this and add typed fields.</p>
 */
public class HarnessConfig {

    private String outputDir;
    private int width;
    private int height;
    private String fontPath;

    public static final String ARABIC_FONT = HarnessFontUtil.resolveFontPath("../src/main/resources/assets/crystalgraphics/IBMPlexSansArabic-Regular.ttf");
    public static final String JAPANESE_FONT = HarnessFontUtil.resolveFontPath("../src/main/resources/assets/crystalgraphics/MPLUSRounded1c-Regular.ttf");

    /**
     * Custom output name prefix for screenshot filenames.
     * When set via {@code --output-name=PREFIX}, scenes use this prefix instead of
     * their default filenames. For example, {@code --output-name=test1} causes
     * {@code world-text-normal.png} to become {@code test1-normal.png}.
     *
     * <p>Null means "use scene default names".</p>
     */
    private String outputName;

    public HarnessConfig() {
        this.outputDir = "gl-debug-harness/harness-output";
        this.width = 800;
        this.height = 600;
        this.fontPath = null;
        this.outputName = null;
    }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String val) { this.outputDir = val; }

    public int getWidth() { return width; }
    public void setWidth(int val) { this.width = val; }

    public int getHeight() { return height; }
    public void setHeight(int val) { this.height = val; }

    /** May be null — callers must fall back to system font discovery. */
    public String getFontPath() { return fontPath; }
    public void setFontPath(String val) { this.fontPath = val; }

    /**
     * Returns the custom output name prefix, or null if not set.
     * When non-null, scenes should use this as the prefix for all output filenames.
     */
    public String getOutputName() { return outputName; }
    public void setOutputName(String val) { this.outputName = val; }

    /**
     * Apply system property overrides (called before CLI args).
     */
    public void applySystemProperties() {
        String dir = System.getProperty("harness.output.dir");
        if (dir != null && !dir.isEmpty()) {
            this.outputDir = dir;
        }
        String fp = System.getProperty("harness.font.path");
        if (fp != null && !fp.isEmpty()) {
            this.fontPath = fp;
        }
        String w = System.getProperty("harness.width");
        if (w != null && !w.isEmpty()) {
            this.width = parseIntStrict(w, "harness.width");
        }
        String h = System.getProperty("harness.height");
        if (h != null && !h.isEmpty()) {
            this.height = parseIntStrict(h, "harness.height");
        }
    }

    /**
     * Apply CLI key=value overrides.
     */
    public void applyCliArgs(Map<String, String> args) {
        if (args.containsKey("output-dir")) {
            this.outputDir = args.get("output-dir");
        }
        if (args.containsKey("font-path")) {
            this.fontPath = args.get("font-path");
        }
        if (args.containsKey("width")) {
            this.width = parseIntStrict(args.get("width"), "--width");
        }
        if (args.containsKey("height")) {
            this.height = parseIntStrict(args.get("height"), "--height");
        }
        if (args.containsKey("output-name")) {
            this.outputName = args.get("output-name");
        }
    }

    public static int parseIntStrict(String value, String paramName) {
        try {
            int n = Integer.parseInt(value);
            if (n <= 0) {
                throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be positive integer)");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must be a valid integer)");
        }
    }

    /**
     * Parse CLI args into a key→value map.
     * Supports: --key=value, --key value, --flag (flag→"true").
     * The --mode arg is excluded from the result.
     */
    public static Map<String, String> parseCliArgs(String[] args) {
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String key;
            String value;
            int eq = arg.indexOf('=');
            if (eq >= 0) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                } else {
                    value = "true";
                }
            }
            if (!"mode".equals(key) && !"list".equals(key) && !"help".equals(key)) {
                map.put(key, value);
            }
        }
        return map;
    }
}
