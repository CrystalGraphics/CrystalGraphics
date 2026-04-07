package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfEdgeColoringMode;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfVerificationConfig;

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
        BITMAP, MSDF, MTSDF, BOTH;

        static AtlasType parse(String value) {
            if (value == null || value.isEmpty()) {
                return BOTH;
            }
            String upper = value.toUpperCase();
            try {
                return AtlasType.valueOf(upper);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid atlas-type '" + value + "'. Valid values: bitmap, msdf, mtsdf, both");
            }
        }
    }

    private AtlasType atlasType = AtlasType.BOTH;
    private int atlasSize = 512;
    private int bitmapPxSize = DEFAULT_BITMAP_PX_SIZE;
    private int msdfPxSize = DEFAULT_MSDF_PX_SIZE;
    private int msdfAtlasScale = CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX;
    private float msdfPxRange = CgMsdfAtlasConfig.DEFAULT_PX_RANGE;
    private String text = ASCII_PRINTABLE_CHARS;

    // ── Parity / overflow knobs (Phase D of atlas overhaul plan) ───────

    /** When true, prewarm all MSDF glyphs deterministically before dump. */
    private boolean parityPrewarm = true;

    /** When true, prewarm all bitmap glyphs deterministically before dump. */
    private boolean prewarmBitmap = false;

    /** When true, dump every atlas page instead of only the first populated one. */
    private boolean dumpAllPages = true;

    /**
     * Override per-page atlas texture dimension. {@link #ATLAS_PAGE_SIZE_AUTO}
     * means auto-compute from font size and glyph count.
     */
    private int atlasPageSize = ATLAS_PAGE_SIZE_AUTO;

    private boolean verifyMsdf = false;
    private int msdfVerifyReferencePx = CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX;
    private float msdfVerifyReconstructionThreshold = CgMsdfVerificationConfig.DEFAULT_RECONSTRUCTION_THRESHOLD;
    private float msdfVerifyReferenceThreshold = CgMsdfVerificationConfig.DEFAULT_REFERENCE_THRESHOLD;
    private float msdfVerifyMaxMismatchRatio = CgMsdfVerificationConfig.DEFAULT_MAX_MISMATCH_RATIO;
    private boolean msdfVerifyDumpPassingGlyphs = false;

    private boolean msdfOverlapSupport = CgMsdfAtlasConfig.DEFAULT_OVERLAP_SUPPORT;
    private int msdfErrorCorrectionMode = CgMsdfAtlasConfig.DEFAULT_ERROR_CORRECTION_MODE;
    private int msdfDistanceCheckMode = CgMsdfAtlasConfig.DEFAULT_DISTANCE_CHECK_MODE;
    private double msdfMinDeviationRatio = CgMsdfAtlasConfig.DEFAULT_MIN_DEVIATION_RATIO;
    private double msdfMinImproveRatio = CgMsdfAtlasConfig.DEFAULT_MIN_IMPROVE_RATIO;
    private int msdfSpacingPx = CgMsdfAtlasConfig.DEFAULT_SPACING_PX;
    private float msdfMiterLimit = CgMsdfAtlasConfig.DEFAULT_MITER_LIMIT;
    private boolean msdfAlignOriginX = CgMsdfAtlasConfig.DEFAULT_ALIGN_ORIGIN_X;
    private boolean msdfAlignOriginY = CgMsdfAtlasConfig.DEFAULT_ALIGN_ORIGIN_Y;
    private CgMsdfEdgeColoringMode msdfEdgeColoringMode = CgMsdfAtlasConfig.DEFAULT_EDGE_COLORING_MODE;
    private double msdfEdgeColoringAngleThreshold = CgMsdfAtlasConfig.DEFAULT_EDGE_COLORING_ANGLE_THRESHOLD;

    private static final String ASCII_PRINTABLE_CHARS = IntStream.range(32, 127)
            .mapToObj(i -> String.valueOf((char) i))
            .collect(Collectors.joining());
    
    public AtlasType getAtlasType() { return atlasType; }
    public int getAtlasSize() { return atlasSize; }
    public int getBitmapPxSize() { return bitmapPxSize; }
    public int getMsdfPxSize() { return msdfPxSize; }
    public int getMsdfAtlasScale() { return msdfAtlasScale; }
    public float getMsdfPxRange() { return msdfPxRange; }
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

    public boolean isVerifyMsdf() { return verifyMsdf; }
    public int getMsdfVerifyReferencePx() { return msdfVerifyReferencePx; }
    public float getMsdfVerifyReconstructionThreshold() { return msdfVerifyReconstructionThreshold; }
    public float getMsdfVerifyReferenceThreshold() { return msdfVerifyReferenceThreshold; }
    public float getMsdfVerifyMaxMismatchRatio() { return msdfVerifyMaxMismatchRatio; }
    public boolean isMsdfVerifyDumpPassingGlyphs() { return msdfVerifyDumpPassingGlyphs; }

    public boolean isMsdfOverlapSupport() { return msdfOverlapSupport; }
    public int getMsdfErrorCorrectionMode() { return msdfErrorCorrectionMode; }
    public int getMsdfDistanceCheckMode() { return msdfDistanceCheckMode; }
    public double getMsdfMinDeviationRatio() { return msdfMinDeviationRatio; }
    public double getMsdfMinImproveRatio() { return msdfMinImproveRatio; }
    public int getMsdfSpacingPx() { return msdfSpacingPx; }
    public float getMsdfMiterLimit() { return msdfMiterLimit; }
    public boolean isMsdfAlignOriginX() { return msdfAlignOriginX; }
    public boolean isMsdfAlignOriginY() { return msdfAlignOriginY; }
    public CgMsdfEdgeColoringMode getMsdfEdgeColoringMode() { return msdfEdgeColoringMode; }
    public double getMsdfEdgeColoringAngleThreshold() { return msdfEdgeColoringAngleThreshold; }

    public CgMsdfAtlasConfig buildMsdfAtlasConfig(int resolvedAtlasPageSize) {
        return new CgMsdfAtlasConfig(
                msdfAtlasScale,
                msdfPxRange,
                resolvedAtlasPageSize,
                msdfSpacingPx,
                msdfMiterLimit,
                msdfAlignOriginX,
                msdfAlignOriginY,
                msdfOverlapSupport,
                msdfErrorCorrectionMode,
                msdfDistanceCheckMode,
                msdfMinDeviationRatio,
                msdfMinImproveRatio,
                msdfEdgeColoringMode,
                msdfEdgeColoringAngleThreshold,
                atlasType == AtlasType.MTSDF);
    }

    public CgMsdfVerificationConfig buildMsdfVerificationConfig() {
        return new CgMsdfVerificationConfig(
                msdfVerifyReferencePx,
                msdfVerifyReconstructionThreshold,
                msdfVerifyReferenceThreshold,
                msdfVerifyMaxMismatchRatio,
                msdfVerifyDumpPassingGlyphs);
    }

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
        String mas = System.getProperty("harness.msdf.atlas.scale");
        if (mas != null && !mas.isEmpty()) {
            this.msdfAtlasScale = parseIntStrict(mas, "harness.msdf.atlas.scale");
        }
        String pxRange = System.getProperty("harness.msdf.px.range");
        if (pxRange != null && !pxRange.isEmpty()) {
            this.msdfPxRange = parsePositiveFloatStrict(pxRange, "harness.msdf.px.range");
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
        applyMsdfSystemProperties();
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
        if (args.containsKey("msdf-atlas-scale")) {
            this.msdfAtlasScale = parseIntStrict(args.get("msdf-atlas-scale"), "--msdf-atlas-scale");
        }
        if (args.containsKey("msdf-px-range")) {
            this.msdfPxRange = parsePositiveFloatStrict(args.get("msdf-px-range"), "--msdf-px-range");
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
        applyMsdfCliArgs(args);
    }

    private void applyMsdfSystemProperties() {
        String verify = System.getProperty("harness.msdf.verify");
        if (verify != null && !verify.isEmpty()) {
            this.verifyMsdf = parseBoolStrict(verify, "harness.msdf.verify");
        }
        String referencePx = System.getProperty("harness.msdf.verify.reference.px");
        if (referencePx != null && !referencePx.isEmpty()) {
            this.msdfVerifyReferencePx = parseIntStrict(referencePx, "harness.msdf.verify.reference.px");
        }
        String reconThreshold = System.getProperty("harness.msdf.verify.reconstruction.threshold");
        if (reconThreshold != null && !reconThreshold.isEmpty()) {
            this.msdfVerifyReconstructionThreshold = parseUnitFloatStrict(reconThreshold, "harness.msdf.verify.reconstruction.threshold");
        }
        String refThreshold = System.getProperty("harness.msdf.verify.reference.threshold");
        if (refThreshold != null && !refThreshold.isEmpty()) {
            this.msdfVerifyReferenceThreshold = parseUnitFloatStrict(refThreshold, "harness.msdf.verify.reference.threshold");
        }
        String mismatch = System.getProperty("harness.msdf.verify.max.mismatch.ratio");
        if (mismatch != null && !mismatch.isEmpty()) {
            this.msdfVerifyMaxMismatchRatio = parseUnitFloatStrict(mismatch, "harness.msdf.verify.max.mismatch.ratio");
        }
        String dumpPasses = System.getProperty("harness.msdf.verify.dump.passing");
        if (dumpPasses != null && !dumpPasses.isEmpty()) {
            this.msdfVerifyDumpPassingGlyphs = parseBoolStrict(dumpPasses, "harness.msdf.verify.dump.passing");
        }
        String overlap = System.getProperty("harness.msdf.overlap.support");
        if (overlap != null && !overlap.isEmpty()) {
            this.msdfOverlapSupport = parseBoolStrict(overlap, "harness.msdf.overlap.support");
        }
        String errorCorrection = System.getProperty("harness.msdf.error.correction.mode");
        if (errorCorrection != null && !errorCorrection.isEmpty()) {
            this.msdfErrorCorrectionMode = parseIntStrict(errorCorrection, "harness.msdf.error.correction.mode");
        }
        String distanceCheck = System.getProperty("harness.msdf.distance.check.mode");
        if (distanceCheck != null && !distanceCheck.isEmpty()) {
            this.msdfDistanceCheckMode = parseIntStrict(distanceCheck, "harness.msdf.distance.check.mode");
        }
        String minDeviation = System.getProperty("harness.msdf.min.deviation.ratio");
        if (minDeviation != null && !minDeviation.isEmpty()) {
            this.msdfMinDeviationRatio = parsePositiveDoubleStrict(minDeviation, "harness.msdf.min.deviation.ratio");
        }
        String minImprove = System.getProperty("harness.msdf.min.improve.ratio");
        if (minImprove != null && !minImprove.isEmpty()) {
            this.msdfMinImproveRatio = parsePositiveDoubleStrict(minImprove, "harness.msdf.min.improve.ratio");
        }
        String spacing = System.getProperty("harness.msdf.spacing.px");
        if (spacing != null && !spacing.isEmpty()) {
            this.msdfSpacingPx = parseIntStrict(spacing, "harness.msdf.spacing.px");
        }
        String miter = System.getProperty("harness.msdf.miter.limit");
        if (miter != null && !miter.isEmpty()) {
            this.msdfMiterLimit = parseNonNegativeFloatStrict(miter, "harness.msdf.miter.limit");
        }
        String alignX = System.getProperty("harness.msdf.align.origin.x");
        if (alignX != null && !alignX.isEmpty()) {
            this.msdfAlignOriginX = parseBoolStrict(alignX, "harness.msdf.align.origin.x");
        }
        String alignY = System.getProperty("harness.msdf.align.origin.y");
        if (alignY != null && !alignY.isEmpty()) {
            this.msdfAlignOriginY = parseBoolStrict(alignY, "harness.msdf.align.origin.y");
        }
        String edgeMode = System.getProperty("harness.msdf.edge.coloring.mode");
        if (edgeMode != null && !edgeMode.isEmpty()) {
            this.msdfEdgeColoringMode = parseEdgeColoringMode(edgeMode, "harness.msdf.edge.coloring.mode");
        }
        String edgeThreshold = System.getProperty("harness.msdf.edge.coloring.angle");
        if (edgeThreshold != null && !edgeThreshold.isEmpty()) {
            this.msdfEdgeColoringAngleThreshold = parsePositiveDoubleStrict(edgeThreshold, "harness.msdf.edge.coloring.angle");
        }
    }

    private void applyMsdfCliArgs(Map<String, String> args) {
        if (args.containsKey("verify-msdf")) {
            this.verifyMsdf = parseBoolStrict(args.get("verify-msdf"), "--verify-msdf");
        }
        if (args.containsKey("msdf-verify-reference-px")) {
            this.msdfVerifyReferencePx = parseIntStrict(args.get("msdf-verify-reference-px"), "--msdf-verify-reference-px");
        }
        if (args.containsKey("msdf-verify-reconstruction-threshold")) {
            this.msdfVerifyReconstructionThreshold = parseUnitFloatStrict(args.get("msdf-verify-reconstruction-threshold"), "--msdf-verify-reconstruction-threshold");
        }
        if (args.containsKey("msdf-verify-reference-threshold")) {
            this.msdfVerifyReferenceThreshold = parseUnitFloatStrict(args.get("msdf-verify-reference-threshold"), "--msdf-verify-reference-threshold");
        }
        if (args.containsKey("msdf-verify-max-mismatch-ratio")) {
            this.msdfVerifyMaxMismatchRatio = parseUnitFloatStrict(args.get("msdf-verify-max-mismatch-ratio"), "--msdf-verify-max-mismatch-ratio");
        }
        if (args.containsKey("msdf-verify-dump-passing")) {
            this.msdfVerifyDumpPassingGlyphs = parseBoolStrict(args.get("msdf-verify-dump-passing"), "--msdf-verify-dump-passing");
        }
        if (args.containsKey("msdf-overlap-support")) {
            this.msdfOverlapSupport = parseBoolStrict(args.get("msdf-overlap-support"), "--msdf-overlap-support");
        }
        if (args.containsKey("msdf-error-correction-mode")) {
            this.msdfErrorCorrectionMode = parseIntStrict(args.get("msdf-error-correction-mode"), "--msdf-error-correction-mode");
        }
        if (args.containsKey("msdf-distance-check-mode")) {
            this.msdfDistanceCheckMode = parseIntStrict(args.get("msdf-distance-check-mode"), "--msdf-distance-check-mode");
        }
        if (args.containsKey("msdf-min-deviation-ratio")) {
            this.msdfMinDeviationRatio = parsePositiveDoubleStrict(args.get("msdf-min-deviation-ratio"), "--msdf-min-deviation-ratio");
        }
        if (args.containsKey("msdf-min-improve-ratio")) {
            this.msdfMinImproveRatio = parsePositiveDoubleStrict(args.get("msdf-min-improve-ratio"), "--msdf-min-improve-ratio");
        }
        if (args.containsKey("msdf-spacing-px")) {
            this.msdfSpacingPx = parseIntStrict(args.get("msdf-spacing-px"), "--msdf-spacing-px");
        }
        if (args.containsKey("msdf-miter-limit")) {
            this.msdfMiterLimit = parseNonNegativeFloatStrict(args.get("msdf-miter-limit"), "--msdf-miter-limit");
        }
        if (args.containsKey("msdf-align-origin-x")) {
            this.msdfAlignOriginX = parseBoolStrict(args.get("msdf-align-origin-x"), "--msdf-align-origin-x");
        }
        if (args.containsKey("msdf-align-origin-y")) {
            this.msdfAlignOriginY = parseBoolStrict(args.get("msdf-align-origin-y"), "--msdf-align-origin-y");
        }
        if (args.containsKey("msdf-edge-coloring-mode")) {
            this.msdfEdgeColoringMode = parseEdgeColoringMode(args.get("msdf-edge-coloring-mode"), "--msdf-edge-coloring-mode");
        }
        if (args.containsKey("msdf-edge-coloring-angle")) {
            this.msdfEdgeColoringAngleThreshold = parsePositiveDoubleStrict(args.get("msdf-edge-coloring-angle"), "--msdf-edge-coloring-angle");
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

    static float parseUnitFloatStrict(String value, String paramName) {
        float parsed = TextSceneConfig.parseFloatStrict(value, paramName);
        if (parsed < 0.0f || parsed > 1.0f) {
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be in range [0,1])");
        }
        return parsed;
    }

    static float parseNonNegativeFloatStrict(String value, String paramName) {
        float parsed = TextSceneConfig.parseFloatStrict(value, paramName);
        if (parsed < 0.0f) {
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be >= 0)");
        }
        return parsed;
    }

    static float parsePositiveFloatStrict(String value, String paramName) {
        float parsed = TextSceneConfig.parseFloatStrict(value, paramName);
        if (parsed <= 0.0f) {
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be > 0)");
        }
        return parsed;
    }

    static double parsePositiveDoubleStrict(String value, String paramName) {
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed <= 0.0d) {
                throw new IllegalArgumentException(
                        "Invalid value for " + paramName + ": '" + value + "' (must be > 0)");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be a valid number)");
        }
    }

    static CgMsdfEdgeColoringMode parseEdgeColoringMode(String value, String paramName) {
        if (value == null || value.isEmpty()) {
            return CgMsdfEdgeColoringMode.SIMPLE;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase();
        try {
            return CgMsdfEdgeColoringMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (valid: simple, ink_trap, distance)");
        }
    }

    public static AtlasDumpConfig create(String[] args) {
        AtlasDumpConfig cfg = new AtlasDumpConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }
}
