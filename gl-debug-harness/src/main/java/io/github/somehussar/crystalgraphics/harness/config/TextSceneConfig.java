package io.github.somehussar.crystalgraphics.harness.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed configuration for the text-scene.
 */
public class TextSceneConfig extends HarnessConfig {

    private String text = "CrystalGraphics font demo - mouse wheel zoom";
    private int atlasSize = 512;
    private int fontSizePx = 24;
    private boolean dumpBitmapAtlas = true;
    private float poseScale = 1.0f;
    private int guiScale = 1;
    private List<Float> scales = new ArrayList<Float>();
    private String outputFilename = null;

    public String getText() { return text; }
    public int getAtlasSize() { return atlasSize; }
    public int getFontSizePx() { return fontSizePx; }
    public boolean isDumpBitmapAtlas() { return dumpBitmapAtlas; }
    public float getPoseScale() { return poseScale; }
    public int getGuiScale() { return guiScale; }
    public String getOutputFilename() { return outputFilename; }

    /**
     * Returns the list of scales for multi-scale comparison rendering.
     * If {@code --scales} was specified, returns those values.
     * Otherwise, returns a single-element list containing {@code poseScale}.
     */
    public List<Float> getEffectiveScales() {
        if (!scales.isEmpty()) {
            return scales;
        }
        List<Float> single = new ArrayList<Float>();
        single.add(poseScale);
        return single;
    }

    /** Returns true if multi-scale comparison mode is active. */
    public boolean isMultiScaleMode() {
        return scales.size() > 1;
    }

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
        if (args.containsKey("pose-scale")) {
            this.poseScale = parseFloatStrict(args.get("pose-scale"), "--pose-scale");
        }
        if (args.containsKey("scales")) {
            this.scales = parseFloatList(args.get("scales"), "--scales");
        }
        if (args.containsKey("output-filename")) {
            this.outputFilename = args.get("output-filename");
        }
        if (args.containsKey("gui-scale")) {
            this.guiScale = parseIntStrict(args.get("gui-scale"), "--gui-scale");
            if (this.guiScale < 1) {
                throw new IllegalArgumentException("--gui-scale must be >= 1, got: " + this.guiScale);
            }
        }
    }

    public static TextSceneConfig create(String[] args) {
        TextSceneConfig cfg = new TextSceneConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }

    static float parseFloatStrict(String value, String paramName) {
        try {
            float f = Float.parseFloat(value);
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be a finite number)");
            }
            return f;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must be a valid float)");
        }
    }

    static List<Float> parseFloatList(String value, String paramName) {
        List<Float> result = new ArrayList<Float>();
        String[] parts = value.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(parseFloatStrict(trimmed, paramName));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must contain at least one scale)");
        }
        return result;
    }
}
