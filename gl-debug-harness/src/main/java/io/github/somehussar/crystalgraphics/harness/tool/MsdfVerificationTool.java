package io.github.somehussar.crystalgraphics.harness.tool;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeException;
import com.msdfgen.Bitmap;
import com.msdfgen.FreeTypeIntegration;
import com.msdfgen.Generator;
import com.msdfgen.Shape;
import com.msdfgen.Transform;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.gl.text.CgMsdfGenerator;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfGlyphLayout;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfVerificationConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class MsdfVerificationTool {

    private static final Logger LOGGER = Logger.getLogger(MsdfVerificationTool.class.getName());

    public VerificationSummary verifyText(CgFont font,
                                          String text,
                                          CgMsdfAtlasConfig atlasConfig,
                                          CgMsdfVerificationConfig verificationConfig,
                                          String outputDir,
                                          String outputPrefix) {
        if (font == null || font.isDisposed()) {
            throw new IllegalArgumentException("font must not be null or disposed");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (atlasConfig == null) {
            throw new IllegalArgumentException("atlasConfig must not be null");
        }
        if (verificationConfig == null) {
            throw new IllegalArgumentException("verificationConfig must not be null");
        }

        List<GlyphVerificationResult> results = new ArrayList<GlyphVerificationResult>();
        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont == null) {
            throw new IllegalStateException("MSDF font integration unavailable for verification: " + font.getKey());
        }

        FTFace face = font.getFtFace();
        Set<Integer> seenGlyphIds = new HashSet<Integer>();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            int glyphId = font.getGlyphIndex(codePoint);
            if (glyphId == 0 || !seenGlyphIds.add(glyphId)) {
                continue;
            }

            GlyphVerificationResult result = verifyGlyph(font, face, msdfFont, glyphId, codePoint,
                    atlasConfig, verificationConfig, outputDir, outputPrefix);
            if (result != null) {
                results.add(result);
            }
        }

        writeSummary(results, atlasConfig, verificationConfig, outputDir, outputPrefix);
        return summarize(results);
    }

    private GlyphVerificationResult verifyGlyph(CgFont font,
                                                FTFace face,
                                                FreeTypeIntegration.Font msdfFont,
                                                int glyphId,
                                                int codePoint,
                                                CgMsdfAtlasConfig atlasConfig,
                                                CgMsdfVerificationConfig verificationConfig,
                                                String outputDir,
                                                String outputPrefix) {
        FreeTypeIntegration.GlyphData glyphData;
        try {
                glyphData = msdfFont.loadGlyphByIndex(glyphId, FreeTypeIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (RuntimeException e) {
            LOGGER.warning("[MsdfVerify] Failed to load glyph " + glyphId + " for codepoint U+"
                    + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT) + ": " + e.getMessage());
            return null;
        }

        Shape shape = glyphData.getShape();
        shape.normalize();
        CgMsdfGenerator.applyEdgeColoring(shape, atlasConfig);
        if (!shape.validate()) {
            LOGGER.warning("[MsdfVerify] Shape validation failed for glyph " + glyphId);
        }

        double[] bounds = shape.getBounds();
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                bounds[0], bounds[1], bounds[2], bounds[3],
                atlasConfig.getAtlasScalePx(), atlasConfig.getPxRange(),
                atlasConfig.getMiterLimit(), atlasConfig.isAlignOriginX(), atlasConfig.isAlignOriginY());
        if (layout.isEmpty()) {
            return null;
        }

        Transform transform = new Transform()
                .scale(layout.getScale())
                .translate(layout.getTranslateX(), layout.getTranslateY())
                .range(-layout.getRangeInShapeUnits(), layout.getRangeInShapeUnits());

        Bitmap msdfBitmap = Bitmap.allocMsdf(layout.getBoxWidth(), layout.getBoxHeight());
        Bitmap reconstructedBitmap = Bitmap.allocSdf(layout.getBoxWidth(), layout.getBoxHeight());
        try {
            Generator.generateMsdf(msdfBitmap, shape, transform,
                    atlasConfig.isOverlapSupport(),
                    atlasConfig.getErrorCorrectionMode(),
                    atlasConfig.getDistanceCheckMode(),
                    atlasConfig.getMinDeviationRatio(),
                    atlasConfig.getMinImproveRatio());
            Generator.renderSdf(reconstructedBitmap, msdfBitmap, transform,
                    verificationConfig.getReconstructionThreshold());

            float[] reconstructed = reconstructedBitmap.getPixelData();
            ReferenceGlyph referenceGlyph = renderReferenceGlyph(font, face, glyphId, verificationConfig.getReferenceRenderPx());
            ComparisonMetrics metrics = compare(referenceGlyph, reconstructed,
                    layout, verificationConfig);

            boolean shouldDump = !metrics.isPassing() || verificationConfig.isDumpPassingGlyphs();
            if (shouldDump) {
                dumpArtifacts(referenceGlyph, reconstructed, metrics, glyphId, codePoint,
                        outputDir, outputPrefix);
            }

            return new GlyphVerificationResult(glyphId, codePoint, metrics.getMismatchRatio(), metrics.isPassing());
        } finally {
            reconstructedBitmap.free();
            msdfBitmap.free();
        }
    }

    private ReferenceGlyph renderReferenceGlyph(CgFont font, FTFace face, int glyphId, int referencePx) {
        try {
            face.setPixelSizes(0, referencePx);
            face.loadGlyph(glyphId, FTLoadFlags.FT_LOAD_DEFAULT);
            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);
            FTBitmap bitmap = face.getGlyphBitmap();
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            byte[] packed = packGray(bitmap);
            return new ReferenceGlyph(
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    packed,
                    metrics.getHoriBearingX() / 64.0f,
                    metrics.getHoriBearingY() / 64.0f,
                    metrics.getWidth() / 64.0f,
                    metrics.getHeight() / 64.0f);
        } catch (FreeTypeException e) {
            throw new IllegalStateException("Failed to render reference glyph " + glyphId, e);
        } finally {
            try {
                face.setPixelSizes(0, font.getKey().getTargetPx());
            } catch (Exception ignored) {
            }
            try {
                font.restoreBaseFontSizeForShaping();
            } catch (Exception ignored) {
            }
        }
    }

    private ComparisonMetrics compare(ReferenceGlyph referenceGlyph,
                                      float[] reconstructed,
                                      CgMsdfGlyphLayout layout,
                                      CgMsdfVerificationConfig verificationConfig) {
        int width = layout.getBoxWidth();
        int height = layout.getBoxHeight();
        BufferedImage diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int mismatches = 0;
        int total = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float reconstructedAlpha = reconstructed[y * width + x];
                float referenceAlpha = sampleReference(referenceGlyph, layout, x, y, width, height);
                boolean reconstructedInside = reconstructedAlpha >= verificationConfig.getReconstructionThreshold();
                boolean referenceInside = referenceAlpha >= verificationConfig.getReferenceThreshold();
                if (reconstructedInside != referenceInside) {
                    mismatches++;
                    diff.setRGB(x, y, 0xFFFF0000);
                } else {
                    int shade = Math.min(255, Math.max(0, (int) (referenceAlpha * 255.0f)));
                    diff.setRGB(x, y, 0xFF000000 | (shade << 16) | (shade << 8) | shade);
                }
            }
        }
        float mismatchRatio = total > 0 ? (float) mismatches / (float) total : 0.0f;
        return new ComparisonMetrics(diff, mismatchRatio, mismatchRatio <= verificationConfig.getMaxMismatchRatio());
    }

    private static float sampleReference(ReferenceGlyph referenceGlyph,
                                         CgMsdfGlyphLayout layout,
                                         int targetX,
                                         int targetY,
                                         int targetWidth,
                                         int targetHeight) {
        if (referenceGlyph.width == 0 || referenceGlyph.height == 0) {
            return 0.0f;
        }
        float scaleX = referenceGlyph.metricsWidth > 0.0f
                ? (referenceGlyph.width / referenceGlyph.metricsWidth)
                : 1.0f;
        float scaleY = referenceGlyph.metricsHeight > 0.0f
                ? (referenceGlyph.height / referenceGlyph.metricsHeight)
                : 1.0f;

        float planeLeftPx = (float) (layout.getPlaneLeft() * layout.getScale());
        float planeTopPx = (float) (layout.getPlaneTop() * layout.getScale());
        int sampleX = Math.round((targetX - planeLeftPx - referenceGlyph.bearingX) * scaleX);
        int sampleY = Math.round((referenceGlyph.bearingY - (targetY - planeTopPx)) * scaleY);

        if (sampleX < 0 || sampleX >= referenceGlyph.width || sampleY < 0 || sampleY >= referenceGlyph.height) {
            return 0.0f;
        }
        int index = sampleY * referenceGlyph.width + sampleX;
        return (referenceGlyph.pixels[index] & 0xFF) / 255.0f;
    }

    private void dumpArtifacts(ReferenceGlyph referenceGlyph,
                               float[] reconstructed,
                               ComparisonMetrics metrics,
                               int glyphId,
                               int codePoint,
                               String outputDir,
                               String outputPrefix) {
        String glyphSuffix = String.format(Locale.ROOT, "%s-glyph-%d-U+%04X", outputPrefix, glyphId, codePoint);
        try {
            ImageIO.write(toGrayImage(referenceGlyph.pixels, referenceGlyph.width, referenceGlyph.height),
                    "PNG", new File(outputDir, glyphSuffix + "-reference.png"));
            ImageIO.write(toGrayImage(reconstructed, metrics.diff.getWidth(), metrics.diff.getHeight()),
                    "PNG", new File(outputDir, glyphSuffix + "-reconstructed.png"));
            ImageIO.write(metrics.diff, "PNG", new File(outputDir, glyphSuffix + "-diff.png"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write verification artifacts for glyph " + glyphId, e);
        }
    }

    private void writeSummary(List<GlyphVerificationResult> results,
                              CgMsdfAtlasConfig atlasConfig,
                              CgMsdfVerificationConfig verificationConfig,
                              String outputDir,
                              String outputPrefix) {
        File summaryFile = new File(outputDir, outputPrefix + "-summary.txt");
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(summaryFile));
            try {
                writer.println("=== MSDF Verification Summary ===");
                writer.println("Glyphs checked: " + results.size());
                int failing = 0;
                float worstMismatch = 0.0f;
                for (GlyphVerificationResult result : results) {
                    if (!result.passing) {
                        failing++;
                    }
                    if (result.mismatchRatio > worstMismatch) {
                        worstMismatch = result.mismatchRatio;
                    }
                }
                writer.println("Failing glyphs: " + failing);
                writer.println("Worst mismatch ratio: " + worstMismatch);
                writer.println("Atlas config: " + atlasConfig);
                writer.println("Verification config: refPx=" + verificationConfig.getReferenceRenderPx()
                        + ", reconstructionThreshold=" + verificationConfig.getReconstructionThreshold()
                        + ", referenceThreshold=" + verificationConfig.getReferenceThreshold()
                        + ", maxMismatchRatio=" + verificationConfig.getMaxMismatchRatio());
                writer.println();
                for (GlyphVerificationResult result : results) {
                    writer.println(String.format(Locale.ROOT,
                            "glyphId=%d codePoint=U+%04X mismatchRatio=%.6f passing=%s",
                            result.glyphId, result.codePoint, result.mismatchRatio, result.passing));
                }
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write MSDF verification summary", e);
        }
    }

    private static VerificationSummary summarize(List<GlyphVerificationResult> results) {
        int failing = 0;
        float worstMismatch = 0.0f;
        for (GlyphVerificationResult result : results) {
            if (!result.passing) {
                failing++;
            }
            if (result.mismatchRatio > worstMismatch) {
                worstMismatch = result.mismatchRatio;
            }
        }
        return new VerificationSummary(results.size(), failing, worstMismatch);
    }

    private static byte[] packGray(FTBitmap bitmap) {
        byte[] source = bitmap.getBuffer();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int pitch = bitmap.getPitch();
        if (width == 0 || height == 0) {
            return new byte[0];
        }
        if (pitch == width) {
            return source;
        }
        byte[] packed = new byte[width * height];
        int absPitch = Math.abs(pitch);
        for (int row = 0; row < height; row++) {
            int srcRow = pitch >= 0 ? row : (height - 1 - row);
            System.arraycopy(source, srcRow * absPitch, packed, row * width, width);
        }
        return packed;
    }

    private static BufferedImage toGrayImage(byte[] pixels, int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = pixels[y * width + x] & 0xFF;
                image.setRGB(x, y, 0xFF000000 | (value << 16) | (value << 8) | value);
            }
        }
        return image;
    }

    private static BufferedImage toGrayImage(float[] pixels, int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = Math.min(255, Math.max(0, (int) (pixels[y * width + x] * 255.0f)));
                image.setRGB(x, y, 0xFF000000 | (value << 16) | (value << 8) | value);
            }
        }
        return image;
    }

    private static final class ReferenceGlyph {
        private final int width;
        private final int height;
        private final byte[] pixels;
        private final float bearingX;
        private final float bearingY;
        private final float metricsWidth;
        private final float metricsHeight;

        private ReferenceGlyph(int width, int height, byte[] pixels,
                               float bearingX, float bearingY,
                               float metricsWidth, float metricsHeight) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.bearingX = bearingX;
            this.bearingY = bearingY;
            this.metricsWidth = metricsWidth;
            this.metricsHeight = metricsHeight;
        }
    }

    private static final class ComparisonMetrics {
        private final BufferedImage diff;
        private final float mismatchRatio;
        private final boolean passing;

        private ComparisonMetrics(BufferedImage diff, float mismatchRatio, boolean passing) {
            this.diff = diff;
            this.mismatchRatio = mismatchRatio;
            this.passing = passing;
        }

        public float getMismatchRatio() {
            return mismatchRatio;
        }

        public boolean isPassing() {
            return passing;
        }
    }

    private static final class GlyphVerificationResult {
        private final int glyphId;
        private final int codePoint;
        private final float mismatchRatio;
        private final boolean passing;

        private GlyphVerificationResult(int glyphId, int codePoint, float mismatchRatio, boolean passing) {
            this.glyphId = glyphId;
            this.codePoint = codePoint;
            this.mismatchRatio = mismatchRatio;
            this.passing = passing;
        }
    }

    public static final class VerificationSummary {
        private final int glyphCount;
        private final int failingGlyphCount;
        private final float worstMismatchRatio;

        public VerificationSummary(int glyphCount, int failingGlyphCount, float worstMismatchRatio) {
            this.glyphCount = glyphCount;
            this.failingGlyphCount = failingGlyphCount;
            this.worstMismatchRatio = worstMismatchRatio;
        }

        public int getGlyphCount() {
            return glyphCount;
        }

        public int getFailingGlyphCount() {
            return failingGlyphCount;
        }

        public float getWorstMismatchRatio() {
            return worstMismatchRatio;
        }
    }
}
