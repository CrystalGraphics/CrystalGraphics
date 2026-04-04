package io.github.somehussar.crystalgraphics.gl.text;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeException;
import com.msdfgen.FreeTypeIntegration;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Render-thread font cache for glyph atlas allocation.
 *
 * <p>Each {@link CgFontKey} gets a bitmap atlas and an MSDF atlas on demand.
 * Bitmap glyphs are rasterized through FreeType and uploaded into the bitmap
 * atlas. MSDF glyphs are generated through {@link CgMsdfGenerator} and stored
 * in the MSDF atlas, with bitmap fallback when MSDF is unavailable or skipped.</p>
 *
 * <p>The registry also wires font disposal to atlas cleanup. It is not thread
 * safe and must only be used on the render thread.</p>
 */
public class CgFontRegistry {

    private static final Logger LOGGER = Logger.getLogger(CgFontRegistry.class.getName());
    private static final int DEFAULT_ATLAS_SIZE = 1024;

    private final Map<CgFontKey, CgGlyphAtlas> bitmapAtlases = new HashMap<CgFontKey, CgGlyphAtlas>();
    private final Map<CgFontKey, CgGlyphAtlas> msdfAtlases = new HashMap<CgFontKey, CgGlyphAtlas>();
    private final Set<CgFontKey> registeredFonts = new HashSet<CgFontKey>();
    private final CgMsdfGenerator msdfGenerator = new CgMsdfGenerator();

    /**
     * Returns the atlas region for a glyph, creating and uploading it if needed.
     *
     * <p>Bitmap keys go through the bitmap rasterization path. MSDF keys try the
     * MSDF path first and fall back to bitmap when generation is unavailable or
     * intentionally skipped.</p>
     */
    public CgAtlasRegion ensureGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        if (font.isDisposed()) {
            throw new IllegalStateException("Cannot ensureGlyph on disposed font: " + font.getKey());
        }

        registerFont(font);
        return key.isMsdf() ? ensureMsdfGlyph(font, key, currentFrame) : ensureBitmapGlyph(font, key, currentFrame);
    }

    public CgGlyphAtlas getBitmapAtlas(CgFontKey key) {
        CgGlyphAtlas atlas = bitmapAtlases.get(key);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(DEFAULT_ATLAS_SIZE, DEFAULT_ATLAS_SIZE, CgGlyphAtlas.Type.BITMAP);
            bitmapAtlases.put(key, atlas);
        }
        return atlas;
    }

    public CgGlyphAtlas getMsdfAtlas(CgFontKey key) {
        CgGlyphAtlas atlas = msdfAtlases.get(key);
        if (atlas == null) {
            atlas = CgGlyphAtlas.create(DEFAULT_ATLAS_SIZE, DEFAULT_ATLAS_SIZE, CgGlyphAtlas.Type.MSDF);
            msdfAtlases.put(key, atlas);
        }
        return atlas;
    }

    public void tickFrame(long frame) {
        for (CgGlyphAtlas atlas : bitmapAtlases.values()) {
            atlas.tickFrame(frame);
        }
        for (CgGlyphAtlas atlas : msdfAtlases.values()) {
            atlas.tickFrame(frame);
        }
        msdfGenerator.tickFrame();
    }

    public void releaseFontAtlases(CgFontKey key) {
        CgGlyphAtlas bitmap = bitmapAtlases.remove(key);
        if (bitmap != null && !bitmap.isDeleted()) {
            bitmap.delete();
        }

        CgGlyphAtlas msdf = msdfAtlases.remove(key);
        if (msdf != null && !msdf.isDeleted()) {
            msdf.delete();
        }
    }

    public void releaseAll() {
        for (CgGlyphAtlas atlas : bitmapAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        bitmapAtlases.clear();

        for (CgGlyphAtlas atlas : msdfAtlases.values()) {
            if (!atlas.isDeleted()) {
                atlas.delete();
            }
        }
        msdfAtlases.clear();
        registeredFonts.clear();
    }

    private void registerFont(final CgFont font) {
        final CgFontKey fontKey = font.getKey();
        if (registeredFonts.add(fontKey)) {
            font.setDisposeListener(new Runnable() {
                @Override
                public void run() {
                    releaseFontAtlases(fontKey);
                    registeredFonts.remove(fontKey);
                }
            });
        }
    }

    private CgAtlasRegion ensureBitmapGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        CgGlyphAtlas atlas = getBitmapAtlas(key.getFontKey());
        CgAtlasRegion cached = atlas.get(key, currentFrame);
        if (cached != null) {
            return cached;
        }

        FTFace face = font.getFtFace();
        try {
            face.setPixelSizes(0, key.getFontKey().getTargetPx());

            int loadFlags = FTLoadFlags.FT_LOAD_DEFAULT;
            if (key.getSubPixelBucket() > 0 && key.getFontKey().getTargetPx() <= 12) {
                loadFlags = FTLoadFlags.FT_LOAD_NO_BITMAP;
            }

            loadGlyphOrFallback(face, key.getGlyphId(), loadFlags);

            if (key.getSubPixelBucket() > 0 && key.getFontKey().getTargetPx() <= 12) {
                face.outlineTranslate(key.getSubPixelBucket() * 16L, 0L);
            }

            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

            FTBitmap bitmap = face.getGlyphBitmap();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width == 0 || height == 0) {
                return new CgAtlasRegion(0, 0, 0, 0, 0, 0, 0, 0, key, 0, 0);
            }

            byte[] pixels = normalizeBitmapBuffer(bitmap);
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            float bearingX = metrics.getHoriBearingX() / 64.0f;
            float bearingY = metrics.getHoriBearingY() / 64.0f;
            return atlas.getOrAllocate(key, pixels, width, height, bearingX, bearingY, currentFrame);
        } catch (FreeTypeException e) {
            LOGGER.log(Level.WARNING, "Failed to rasterize glyph " + key, e);
            return null;
        }
    }

    private CgAtlasRegion ensureMsdfGlyph(CgFont font, CgGlyphKey key, long currentFrame) {
        CgGlyphAtlas msdfAtlas = getMsdfAtlas(key.getFontKey());
        CgAtlasRegion cached = msdfAtlas.get(key, currentFrame);
        if (cached != null) {
            return cached;
        }

        FreeTypeIntegration.Font msdfFont = font.getMsdfFont();
        if (msdfFont != null) {
            CgAtlasRegion region = msdfGenerator.queueOrGenerate(
                    key, msdfFont, msdfAtlas, 0f, 0f, currentFrame);
            if (region != null) {
                return region;
            }
        }

        CgGlyphKey bitmapKey = new CgGlyphKey(key.getFontKey(), key.getGlyphId(), false, key.getSubPixelBucket());
        return ensureBitmapGlyph(font, bitmapKey, currentFrame);
    }

    private void loadGlyphOrFallback(FTFace face, int glyphIndex, int loadFlags) throws FreeTypeException {
        try {
            face.loadGlyph(glyphIndex, loadFlags);
        } catch (FreeTypeException e) {
            LOGGER.fine("Glyph " + glyphIndex + " not found, falling back to .notdef");
            face.loadGlyph(0, loadFlags);
        }
    }

    private byte[] normalizeBitmapBuffer(FTBitmap bitmap) {
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
