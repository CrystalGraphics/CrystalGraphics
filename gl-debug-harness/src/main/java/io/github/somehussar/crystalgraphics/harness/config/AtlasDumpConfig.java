package io.github.somehussar.crystalgraphics.harness.config;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class AtlasDumpConfig extends HarnessConfig {

    public static final int DEFAULT_BITMAP_PX_SIZE = 24;
    public static final int DEFAULT_MSDF_PX_SIZE = 32;
    public static final int MIN_MSDF_PX_SIZE = 32;

    public enum AtlasType {
        BITMAP, MSDF, BOTH;

        static AtlasType parse(String value) {
            if (value == null || value.isEmpty()) {
                return BOTH;
            }
            String upper = value.toUpperCase();
            try {
                return AtlasType.valueOf(upper);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid atlas-type '" + value + "'. Valid values: bitmap, msdf, both");
            }
        }
    }

    private AtlasType atlasType = AtlasType.BOTH;
    private int atlasSize = 512;
    private int bitmapPxSize = DEFAULT_BITMAP_PX_SIZE;
    private int msdfPxSize = DEFAULT_MSDF_PX_SIZE;
    private String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    
    String ASCII_Printable_Chars = IntStream.range(32, 127).mapToObj(i -> String.valueOf((char) i))
                                            .collect(Collectors.joining());
    
    public AtlasType getAtlasType() { return atlasType; }
    public int getAtlasSize() { return atlasSize; }
    public int getBitmapPxSize() { return bitmapPxSize; }
    public int getMsdfPxSize() { return msdfPxSize; }
    public String getText() { return ASCII_Printable_Chars; }

    @Override
    public void applySystemProperties() {
        super.applySystemProperties();
        String at = System.getProperty("harness.atlas.type");
        if (at != null && !at.isEmpty()) {
            this.atlasType = AtlasType.parse(at);
        }
        String as = System.getProperty("harness.atlas.size");
        if (as != null && !as.isEmpty()) {
            this.atlasSize = parseIntStrict(as, "harness.atlas.size");
        }
        String bps = System.getProperty("harness.bitmap.px.size");
        if (bps != null && !bps.isEmpty()) {
            this.bitmapPxSize = parseIntStrict(bps, "harness.bitmap.px.size");
        }
        String mps = System.getProperty("harness.msdf.px.size");
        if (mps != null && !mps.isEmpty()) {
            this.msdfPxSize = parseIntStrict(mps, "harness.msdf.px.size");
        }
        String fs = System.getProperty("harness.font.size.px");
        if (fs != null && !fs.isEmpty()) {
            int shared = parseIntStrict(fs, "harness.font.size.px");
            this.bitmapPxSize = shared;
            this.msdfPxSize = shared;
        }
    }

    @Override
    public void applyCliArgs(Map<String, String> args) {
        super.applyCliArgs(args);
        if (args.containsKey("atlas-type")) {
            this.atlasType = AtlasType.parse(args.get("atlas-type"));
        }
        if (args.containsKey("atlas-size")) {
            this.atlasSize = parseIntStrict(args.get("atlas-size"), "--atlas-size");
        }
        if (args.containsKey("font-size-px")) {
            int shared = parseIntStrict(args.get("font-size-px"), "--font-size-px");
            this.bitmapPxSize = shared;
            this.msdfPxSize = shared;
        }
        if (args.containsKey("bitmap-px-size")) {
            this.bitmapPxSize = parseIntStrict(args.get("bitmap-px-size"), "--bitmap-px-size");
        }
        if (args.containsKey("msdf-px-size")) {
            this.msdfPxSize = parseIntStrict(args.get("msdf-px-size"), "--msdf-px-size");
        }
        if (args.containsKey("text")) {
            this.text = args.get("text");
        }
    }

    public static AtlasDumpConfig create(String[] args) {
        AtlasDumpConfig cfg = new AtlasDumpConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }
}
