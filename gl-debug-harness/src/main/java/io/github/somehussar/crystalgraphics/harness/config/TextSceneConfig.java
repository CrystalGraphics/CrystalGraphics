package io.github.somehussar.crystalgraphics.harness.config;

import java.util.Map;

/**
 * Typed configuration for the text-scene.
 */
public class TextSceneConfig extends HarnessConfig {

    private String text = "Hello, this is a test";
    private int atlasSize = 512;
    private int fontSizePx = 32;
    private boolean dumpBitmapAtlas = true;

    public String getText() { return text; }
    public int getAtlasSize() { return atlasSize; }
    public int getFontSizePx() { return fontSizePx; }
    public boolean isDumpBitmapAtlas() { return dumpBitmapAtlas; }

    @Override
   public void applySystemProperties() {
        super.applySystemProperties();
        String fs = System.getProperty("harness.font.size.px");
        if (fs != null && !fs.isEmpty()) {
            this.fontSizePx = parseIntStrict(fs, "harness.font.size.px");
        }
        String as = System.getProperty("harness.atlas.size");
        if (as != null && !as.isEmpty()) {
            this.atlasSize = parseIntStrict(as, "harness.atlas.size");
        }
    }

    @Override
   public void applyCliArgs(Map<String, String> args) {
        super.applyCliArgs(args);
        if (args.containsKey("text")) {
            this.text = args.get("text");
        }
        if (args.containsKey("atlas-size")) {
            this.atlasSize = parseIntStrict(args.get("atlas-size"), "--atlas-size");
        }
        if (args.containsKey("font-size-px")) {
            this.fontSizePx = parseIntStrict(args.get("font-size-px"), "--font-size-px");
        }
        if (args.containsKey("dump-bitmap-atlas")) {
            this.dumpBitmapAtlas = "true".equalsIgnoreCase(args.get("dump-bitmap-atlas"));
        }
    }

    public static TextSceneConfig create(String[] args) {
        TextSceneConfig cfg = new TextSceneConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }
}
