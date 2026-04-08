package io.github.somehussar.crystalgraphics.text.cache;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeException;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import com.crystalgraphics.msdfgen.FreeTypeIntegration;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontVariation;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfGenerator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-worker-thread FreeType/msdfgen context for async glyph generation.
 *
 * <p>Each worker in {@link CgGlyphGenerationExecutor} owns one instance.
 * It manages thread-local FreeType faces and msdfgen font handles,
 * rasterizing bitmap or MSDF glyphs from {@link CgGlyphGenerationJob}
 * descriptors into {@link CgGlyphGenerationResult} payloads.</p>
 */
final class CgWorkerFontContext {

    private final FreeTypeLibrary ftLibrary;
    private final Map<CgFontKey, FTFace> bitmapFaces = new HashMap<CgFontKey, FTFace>();
    private FreeTypeIntegration msdfIntegration;
    private final Map<CgFontKey, FreeTypeIntegration.Font> msdfFonts = new HashMap<CgFontKey, FreeTypeIntegration.Font>();

    CgWorkerFontContext() {
        this.ftLibrary = FreeTypeLibrary.create();
    }

    CgGlyphGenerationResult generateBitmap(CgGlyphGenerationJob job) {
        FTFace face = getBitmapFace(job.getSourceFontKey(), job.getFontBytes());
        face.setPixelSizes(0, job.getEffectiveTargetPx());

        int loadFlags = FTLoadFlags.FT_LOAD_DEFAULT;
        boolean useSubPixel = job.getSubPixelBucket() > 0
                && job.getEffectiveTargetPx() < CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX;
        if (useSubPixel) {
            loadFlags = FTLoadFlags.FT_LOAD_NO_BITMAP;
        }

        loadGlyphOrFallback(face, job.getAtlasKey().getGlyphId(), loadFlags);
        if (useSubPixel) {
            face.outlineTranslate(job.getSubPixelBucket() * 16L, 0L);
        }
        face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

        FTBitmap bitmap = face.getGlyphBitmap();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width == 0 || height == 0) {
            return CgGlyphGenerationResult.emptyBitmap(
                    job.getSourceFontKey(),
                    job.getAtlasKey(),
                    job.getBitmapRasterKey());
        }

        byte[] pixels = normalizeBitmapBuffer(bitmap);
        FTGlyphMetrics metrics = face.getGlyphMetrics();
        float bearingX = metrics.getHoriBearingX() / 64.0f;
        float bearingY = metrics.getHoriBearingY() / 64.0f;
        float metricsWidth;
        float metricsHeight;
        int basePx = job.getSourceFontKey().getTargetPx();
        if (job.getEffectiveTargetPx() != basePx) {
            face.setPixelSizes(0, basePx);
            loadGlyphOrFallback(face, job.getAtlasKey().getGlyphId(), FTLoadFlags.FT_LOAD_DEFAULT);
            FTGlyphMetrics baseMetrics = face.getGlyphMetrics();
            bearingX = baseMetrics.getHoriBearingX() / 64.0f;
            bearingY = baseMetrics.getHoriBearingY() / 64.0f;
            metricsWidth = baseMetrics.getWidth() / 64.0f;
            metricsHeight = baseMetrics.getHeight() / 64.0f;
        } else {
            metricsWidth = metrics.getWidth() / 64.0f;
            metricsHeight = metrics.getHeight() / 64.0f;
        }
        return CgGlyphGenerationResult.bitmap(
                job.getSourceFontKey(),
                job.getAtlasKey(),
                job.getBitmapRasterKey(),
                pixels,
                width,
                height,
                bearingX,
                bearingY,
                metricsWidth,
                metricsHeight);
    }

    CgGlyphGenerationResult generateMsdf(CgGlyphGenerationJob job) {
        FreeTypeIntegration.Font msdfFont = getMsdfFont(job.getSourceFontKey(), job.getFontBytes());
        return CgMsdfGenerator.preparePagedGlyph(
                job.getAtlasKey(),
                job.getSourceFontKey(),
                msdfFont,
                job.getMsdfAtlasKey(),
                job.getMsdfConfig());
    }

    void close() {
        for (FreeTypeIntegration.Font font : msdfFonts.values()) {
            try {
                font.destroy();
            } catch (Exception ignored) {
            }
        }
        msdfFonts.clear();

        if (msdfIntegration != null) {
            try {
                msdfIntegration.destroy();
            } catch (Exception ignored) {
            }
            msdfIntegration = null;
        }

        for (FTFace face : bitmapFaces.values()) {
            try {
                face.destroy();
            } catch (Exception ignored) {
            }
        }
        bitmapFaces.clear();

        try {
            ftLibrary.destroy();
        } catch (Exception ignored) {
        }
    }

    private FTFace getBitmapFace(CgFontKey key, byte[] fontBytes) {
        FTFace cached = bitmapFaces.get(key);
        if (cached != null && !cached.isDestroyed()) {
            return cached;
        }
        FTFace created = ftLibrary.newFaceFromMemory(fontBytes, 0);
        applyVariations(created, key.getVariations());
        bitmapFaces.put(key, created);
        return created;
    }

    private FreeTypeIntegration.Font getMsdfFont(CgFontKey key, byte[] fontBytes) {
        FreeTypeIntegration.Font cached = msdfFonts.get(key);
        if (cached != null && !cached.isDestroyed()) {
            return cached;
        }
        if (msdfIntegration == null) {
            msdfIntegration = FreeTypeIntegration.create();
        }
        FreeTypeIntegration.Font created = msdfIntegration.loadFontData(fontBytes);
        applyVariations(created, key.getVariations());
        msdfFonts.put(key, created);
        return created;
    }

    private static void applyVariations(FTFace face, List<CgFontVariation> variations) {
        if (variations == null || variations.isEmpty()) {
            return;
        }
        face.setVariationCoordinates(toVariationTags(variations), toVariationValues(variations));
    }

    private static void applyVariations(FreeTypeIntegration.Font font, List<CgFontVariation> variations) {
        if (variations == null || variations.isEmpty()) {
            return;
        }
        font.setVariations(toVariationTags(variations), toVariationValues(variations));
    }

    private static String[] toVariationTags(List<CgFontVariation> variations) {
        String[] tags = new String[variations.size()];
        for (int i = 0; i < variations.size(); i++) {
            tags[i] = variations.get(i).getTag();
        }
        return tags;
    }

    private static float[] toVariationValues(List<CgFontVariation> variations) {
        float[] values = new float[variations.size()];
        for (int i = 0; i < variations.size(); i++) {
            values[i] = variations.get(i).getValue();
        }
        return values;
    }

    private static void loadGlyphOrFallback(FTFace face, int glyphIndex, int loadFlags) {
        try {
            face.loadGlyph(glyphIndex, loadFlags);
        } catch (FreeTypeException e) {
            face.loadGlyph(0, loadFlags);
        }
    }

    private static byte[] normalizeBitmapBuffer(FTBitmap bitmap) {
        byte[] source = bitmap.getBuffer();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int pitch = bitmap.getPitch();
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
}
