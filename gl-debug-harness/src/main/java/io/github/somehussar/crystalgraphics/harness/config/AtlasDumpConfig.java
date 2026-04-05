package io.github.somehussar.crystalgraphics.harness.config;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Configuration for the atlas-dump harness scene.
 *
 * <p>Supports the standard harness config precedence (defaults → system properties → CLI args)
 * plus atlas-specific knobs for multi-page dumping, parity prewarm, and forced pagination
 * scenarios.</p>
 *
 * <h3>Parity / Overflow CLI Knobs</h3>
 * <ul>
 *   <li>{@code --parity-prewarm=true} — Deterministic MSDF prewarm: loads the full glyph set
 *       up front and generates all MSDF glyphs before capturing, producing dense atlas packing
 *       comparable to {@code msdf-atlas-gen} static output.</li>
 *   <li>{@code --prewarm-bitmap=true} — Same concept for bitmap glyphs: ensures all bitmap
 *       glyphs are rasterized and allocated before dump.</li>
 *   <li>{@code --dump-all-pages=true} — Dump every atlas page (not just the first populated one).
 *       Each page gets its own PNG and manifest entry.</li>
 *   <li>{@code --atlas-page-size=N} — Override the per-page atlas texture dimension (default: auto).
 *       Useful for forcing pagination: e.g., {@code --atlas-page-size=512} with 128px MSDF glyphs
 *       forces overflow to additional pages quickly.</li>
 * </ul>
 *
 * <h3>Expected Filename Pattern</h3>
 * <p>When {@code dump-all-pages} is active:</p>
 * <ul>
 *   <li>{@code msdf-atlas-dump-128px-page-0.png}</li>
 *   <li>{@code msdf-atlas-dump-128px-page-0-manifest.txt}</li>
 *   <li>{@code msdf-atlas-dump-128px-page-1.png} (when overflow occurs)</li>
 *   <li>{@code bitmap-atlas-dump-24px-page-0.png}</li>
 * </ul>
 */
public final class AtlasDumpConfig extends HarnessConfig {

    public static final int DEFAULT_BITMAP_PX_SIZE = 24;
    public static final int DEFAULT_MSDF_PX_SIZE = 32;
    public static final int MIN_MSDF_PX_SIZE = 32;

    /**
     * Sentinel value indicating that atlas page size should be auto-computed
     * from font size and glyph count (the legacy {@code computeAtlasSize} path).
     */
    public static final int ATLAS_PAGE_SIZE_AUTO = -1;

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
    private String text = ASCII_PRINTABLE_CHARS;

    // ── Parity / overflow knobs (Phase D of atlas overhaul plan) ───────

    /** When true, prewarm all MSDF glyphs deterministically before dump. */
    private boolean parityPrewarm = false;

    /** When true, prewarm all bitmap glyphs deterministically before dump. */
    private boolean prewarmBitmap = false;

    /** When true, dump every atlas page instead of only the first populated one. */
    private boolean dumpAllPages = false;

    /**
     * Override per-page atlas texture dimension. {@link #ATLAS_PAGE_SIZE_AUTO}
     * means auto-compute from font size and glyph count.
     */
    private int atlasPageSize = ATLAS_PAGE_SIZE_AUTO;

    private static final String ASCII_PRINTABLE_CHARS = IntStream.range(32, 127)
            .mapToObj(i -> String.valueOf((char) i))
            .collect(Collectors.joining());
    
    public AtlasType getAtlasType() { return atlasType; }
    public int getAtlasSize() { return atlasSize; }
    public int getBitmapPxSize() { return bitmapPxSize; }
    public int getMsdfPxSize() { return msdfPxSize; }
    public String getText() { return text; }

    /** Whether deterministic MSDF parity prewarm is enabled. */
    public boolean isParityPrewarm() { return parityPrewarm; }

    /** Whether deterministic bitmap prewarm is enabled. */
    public boolean isPrewarmBitmap() { return prewarmBitmap; }

    /** Whether all atlas pages should be dumped (not just the first). */
    public boolean isDumpAllPages() { return dumpAllPages; }

    /**
     * Returns the per-page atlas texture dimension override, or
     * {@link #ATLAS_PAGE_SIZE_AUTO} if auto-sizing should be used.
     */
    public int getAtlasPageSize() { return atlasPageSize; }

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

        // Parity / overflow system properties
        String pp = System.getProperty("harness.parity.prewarm");
        if (pp != null && !pp.isEmpty()) {
            this.parityPrewarm = parseBoolStrict(pp, "harness.parity.prewarm");
        }
        String pb = System.getProperty("harness.prewarm.bitmap");
        if (pb != null && !pb.isEmpty()) {
            this.prewarmBitmap = parseBoolStrict(pb, "harness.prewarm.bitmap");
        }
        String dap = System.getProperty("harness.dump.all.pages");
        if (dap != null && !dap.isEmpty()) {
            this.dumpAllPages = parseBoolStrict(dap, "harness.dump.all.pages");
        }
        String aps = System.getProperty("harness.atlas.page.size");
        if (aps != null && !aps.isEmpty()) {
            this.atlasPageSize = parseIntStrict(aps, "harness.atlas.page.size");
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

        // Parity / overflow CLI knobs
        if (args.containsKey("parity-prewarm")) {
            this.parityPrewarm = parseBoolStrict(args.get("parity-prewarm"), "--parity-prewarm");
        }
        if (args.containsKey("prewarm-bitmap")) {
            this.prewarmBitmap = parseBoolStrict(args.get("prewarm-bitmap"), "--prewarm-bitmap");
        }
        if (args.containsKey("dump-all-pages")) {
            this.dumpAllPages = parseBoolStrict(args.get("dump-all-pages"), "--dump-all-pages");
        }
        if (args.containsKey("atlas-page-size")) {
            this.atlasPageSize = parseIntStrict(args.get("atlas-page-size"), "--atlas-page-size");
        }
    }

    /**
     * Parses a boolean string strictly: accepts "true" or "false" (case-insensitive).
     *
     * @param value     the string value
     * @param paramName parameter name for error messages
     * @return parsed boolean
     * @throws IllegalArgumentException if value is not "true" or "false"
     */
    static boolean parseBoolStrict(String value, String paramName) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must be 'true' or 'false')");
    }

    public static AtlasDumpConfig create(String[] args) {
        AtlasDumpConfig cfg = new AtlasDumpConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }
}
