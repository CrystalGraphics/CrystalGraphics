package io.github.somehussar.crystalgraphics.api.font;

import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTVariationAxisInfo;
import com.crystalgraphics.freetype.FreeTypeException;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Public API handle for a loaded font.
 *
 * <p>A {@code CgFont} can exist in two modes:</p>
 * <ul>
 *   <li><strong>Unsized/base</strong> — created from bytes/path/style only. It keeps
 *       enough native state for glyph coverage queries and can lazily vend cached
 *       size-bound variants via {@link #atSize(int)}.</li>
 *   <li><strong>Size-bound</strong> — created with a concrete target pixel size. This
 *       is the renderable/shapable form used by {@link CgTextLayoutBuilder} and
 *       {@code CgTextRenderer}.</li>
 * </ul>
 *
 * <p>This preserves the existing atlas/shaping architecture, which still requires a
 * concrete pixel size internally, while letting callers treat the uploaded font data
 * as a reusable logical font asset.</p>
 */
public class CgFont {

    private static final Logger LOGGER = Logger.getLogger(CgFont.class.getName());

    private final String logicalName;
    private final CgFontStyle style;
    private final List<CgFontVariation> variations;
    private final byte[] fontBytes;
    private final boolean sizeBound;
    private final CgFontKey key;
    private final CgFontMetrics metrics;
    private final List<CgFontAxisInfo> variationAxes;
    private final CgFont baseFont;
    private final Map<Integer, CgFont> sizedVariants;

    private FreeTypeLibrary ftLibrary;
    private FTFace ftFace;
    private HBFont hbFont;

    private FreeTypeMSDFIntegration msdfFtInstance;
    private FreeTypeMSDFIntegration.Font msdfFtFont;

    private boolean disposed;
    private Runnable disposeListener;

    private CgFont(String logicalName,
                   CgFontStyle style,
                   byte[] fontBytes,
                   List<CgFontVariation> variations,
                   boolean sizeBound,
                   Integer targetPx,
                   CgFontMetrics metrics,
                   List<CgFontAxisInfo> variationAxes,
                   FreeTypeLibrary ftLibrary,
                   FTFace ftFace,
                   HBFont hbFont,
                   CgFont baseFont) {
        this.logicalName = logicalName;
        this.style = style;
        this.fontBytes = fontBytes;
        this.variations = variations;
        this.sizeBound = sizeBound;
        this.key = sizeBound ? new CgFontKey(logicalName, style, targetPx.intValue(), variations) : null;
        this.metrics = metrics;
        this.variationAxes = variationAxes;
        this.ftLibrary = ftLibrary;
        this.ftFace = ftFace;
        this.hbFont = hbFont;
        this.baseFont = baseFont;
        this.sizedVariants = baseFont == null ? new HashMap<Integer, CgFont>() : null;
        this.disposed = false;
    }

    public static CgFont load(String fontPath, CgFontStyle style) {
        return load(fontPath, style, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(String fontPath,
                              CgFontStyle style,
                              List<CgFontVariation> variations) {
        if (fontPath == null) {
            throw new IllegalArgumentException("fontPath must not be null");
        }
        byte[] data;
        try {
            data = readFileBytes(fontPath);
        } catch (IOException e) {
            throw new FreeTypeException(0, "Failed to read font file: " + fontPath + " — " + e.getMessage());
        }
        return loadUnsizedFromBytes(data, fontPath, style, variations);
    }

    public static CgFont load(String fontPath, CgFontStyle style, int targetPx) {
        return load(fontPath, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(String fontPath,
                              CgFontStyle style,
                              int targetPx,
                              List<CgFontVariation> variations) {
        if (fontPath == null) {
            throw new IllegalArgumentException("fontPath must not be null");
        }
        byte[] data;
        try {
            data = readFileBytes(fontPath);
        } catch (IOException e) {
            throw new FreeTypeException(0, "Failed to read font file: " + fontPath + " — " + e.getMessage());
        }
        return loadSizedFromBytes(data, fontPath, style, targetPx, variations, null);
    }

    public static CgFont load(byte[] fontData, String logicalName, CgFontStyle style) {
        return load(fontData, logicalName, style, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(byte[] fontData,
                              String logicalName,
                              CgFontStyle style,
                              List<CgFontVariation> variations) {
        validateFontBytes(fontData, logicalName, style);
        return loadUnsizedFromBytes(fontData, logicalName, style, variations);
    }

    public static CgFont load(byte[] fontData, String logicalName,
                              CgFontStyle style, int targetPx) {
        return load(fontData, logicalName, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(byte[] fontData, String logicalName,
                              CgFontStyle style, int targetPx,
                              List<CgFontVariation> variations) {
        validateFontBytes(fontData, logicalName, style);
        return loadSizedFromBytes(fontData, logicalName, style, targetPx, variations, null);
    }

    private static CgFont loadUnsizedFromBytes(byte[] data,
                                               String logicalName,
                                               CgFontStyle style,
                                               List<CgFontVariation> variations) {
        validateFontBytes(data, logicalName, style);
        List<CgFontVariation> canonicalVariations = CgFontKey.canonicalizeVariations(variations);
        LoadedNativeState state = loadNativeState(data, canonicalVariations, null, false);
        return new CgFont(logicalName, style, data, canonicalVariations,
                false, null, null, state.variationAxes,
                state.ftLibrary, state.ftFace, null, null);
    }

    private static CgFont loadSizedFromBytes(byte[] data,
                                             String logicalName,
                                             CgFontStyle style,
                                             int targetPx,
                                             List<CgFontVariation> variations,
                                             CgFont baseFont) {
        validateFontBytes(data, logicalName, style);
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        List<CgFontVariation> canonicalVariations = CgFontKey.canonicalizeVariations(variations);
        LoadedNativeState state = loadNativeState(data, canonicalVariations, Integer.valueOf(targetPx), true);
        return new CgFont(logicalName, style, data, canonicalVariations,
                true, Integer.valueOf(targetPx), state.metrics, state.variationAxes,
                state.ftLibrary, state.ftFace, state.hbFont, baseFont);
    }

    private static LoadedNativeState loadNativeState(byte[] data,
                                                     List<CgFontVariation> variations,
                                                     Integer targetPx,
                                                     boolean createHbFont) {
        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = null;
        HBFont hbFont = null;
        try {
            face = ftLib.newFaceFromMemory(data, 0);
            applyVariationsToFace(face, variations);
            if (targetPx != null) {
                face.setPixelSizes(0, targetPx.intValue());
            }

            List<CgFontAxisInfo> availableAxes = extractVariationAxes(face);
            CgFontMetrics metrics = null;
            if (createHbFont) {
                hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
                applyVariationsToHbFont(hbFont, variations);
                metrics = extractMetrics(face, targetPx.intValue());
            }
            return new LoadedNativeState(ftLib, face, hbFont, availableAxes, metrics);
        } catch (RuntimeException e) {
            destroyQuietly(hbFont, face, ftLib);
            throw e;
        } catch (Error e) {
            destroyQuietly(hbFont, face, ftLib);
            throw e;
        }
    }

    public boolean isSizeBound() {
        return sizeBound;
    }

    public String getLogicalName() {
        return logicalName;
    }

    public CgFontStyle getStyle() {
        return style;
    }

    public int getTargetPx() {
        requireSizeBound("This font has no target pixel size. Call atSize(int) first.");
        return key.getTargetPx();
    }

    public CgFont atSize(int targetPx) {
        checkNotDisposed();
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        if (sizeBound && key.getTargetPx() == targetPx) {
            return this;
        }
        if (baseFont != null) {
            return baseFont.atSize(targetPx);
        }

        Integer cacheKey = Integer.valueOf(targetPx);
        CgFont cached = sizedVariants.get(cacheKey);
        if (cached != null && !cached.isDisposed()) {
            return cached;
        }

        CgFont sized = loadSizedFromBytes(fontBytes, logicalName, style, targetPx, variations, this);
        sizedVariants.put(cacheKey, sized);
        return sized;
    }

    public CgFontKey getKey() {
        requireSizeBound("This font has no size-bound key. Call atSize(int) first.");
        return key;
    }

    public CgFontMetrics getMetrics() {
        requireSizeBound("This font has no size-bound metrics. Call atSize(int) first.");
        return metrics;
    }

    public List<CgFontVariation> getVariations() {
        return variations;
    }

    public List<CgFontAxisInfo> getVariationAxes() {
        return variationAxes;
    }

    public boolean isVariableFont() {
        return !variationAxes.isEmpty();
    }

    public boolean canDisplayCodePoint(int codePoint) {
        return getGlyphIndex(codePoint) != 0;
    }

    public int getGlyphIndex(int codePoint) {
        checkNotDisposed();
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
            return 0;
        }
        return ftFace != null ? ftFace.getCharIndex(codePoint) : 0;
    }

    public byte[] getFontBytes() {
        checkNotDisposed();
        return fontBytes;
    }

    public boolean isDisposed() {
        return disposed;
    }

    public void setDisposeListener(Runnable listener) {
        this.disposeListener = listener;
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        if (sizedVariants != null && !sizedVariants.isEmpty()) {
            List<CgFont> variants = new ArrayList<CgFont>(sizedVariants.values());
            sizedVariants.clear();
            for (CgFont variant : variants) {
                if (variant != null) {
                    variant.dispose();
                }
            }
        }

        if (baseFont != null) {
            baseFont.detachSizedVariant(this);
        }

        if (disposeListener != null) {
            try {
                disposeListener.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in dispose listener", e);
            }
            disposeListener = null;
        }

        if (msdfFtFont != null) {
            try {
                msdfFtFont.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying MSDF font", e);
            }
            msdfFtFont = null;
        }
        if (msdfFtInstance != null) {
            try {
                msdfFtInstance.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying MSDF FreeTypeIntegration", e);
            }
            msdfFtInstance = null;
        }

        if (hbFont != null) {
            try {
                hbFont.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying HBFont", e);
            }
            hbFont = null;
        }
        if (ftFace != null) {
            try {
                ftFace.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying FTFace", e);
            }
            ftFace = null;
        }
        if (ftLibrary != null) {
            try {
                ftLibrary.destroy();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error destroying FreeTypeLibrary", e);
            }
            ftLibrary = null;
        }
    }

    HBFont getHbFontInternal() {
        checkNotDisposed();
        requireSizeBound("Text shaping requires a size-bound font. Call atSize(int) first.");
        return hbFont;
    }

    public FTFace getFtFace() {
        checkNotDisposed();
        requireSizeBound("Glyph rasterization requires a size-bound font. Call atSize(int) first.");
        return ftFace;
    }

    public void restoreBaseFontSizeForShaping() {
        checkNotDisposed();
        requireSizeBound("Cannot restore shaping size on an unsized font.");
        if (ftFace == null) {
            return;
        }
        ftFace.setPixelSizes(0, key.getTargetPx());
        if (hbFont != null && !hbFont.isDestroyed()) {
            FreeTypeHarfBuzzIntegration.syncFontMetrics(hbFont, ftFace);
        }
    }

    public FreeTypeMSDFIntegration.Font getMsdfFont() {
        checkNotDisposed();
        requireSizeBound("MSDF generation requires a size-bound font. Call atSize(int) first.");
        if (msdfFtFont != null) {
            return msdfFtFont;
        }

        if (!FreeTypeMSDFIntegration.isAvailable()) {
            LOGGER.warning("MSDF FreeTypeIntegration is not available; "
                    + "MSDF generation will be skipped for font: " + logicalName);
            return null;
        }

        try {
            msdfFtInstance = FreeTypeMSDFIntegration.create();
            msdfFtFont = msdfFtInstance.loadFontData(fontBytes);
            applyVariationsToMsdfFont(msdfFtFont, variations);
            return msdfFtFont;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to initialize MSDF font for: " + logicalName, e);
            if (msdfFtFont != null) {
                try {
                    msdfFtFont.destroy();
                } catch (Exception ignored) {
                }
                msdfFtFont = null;
            }
            if (msdfFtInstance != null) {
                try {
                    msdfFtInstance.destroy();
                } catch (Exception ignored) {
                }
                msdfFtInstance = null;
            }
            return null;
        }
    }

    private void detachSizedVariant(CgFont variant) {
        if (sizedVariants == null || variant == null || !variant.sizeBound) {
            return;
        }
        CgFont cached = sizedVariants.get(Integer.valueOf(variant.key.getTargetPx()));
        if (cached == variant) {
            sizedVariants.remove(Integer.valueOf(variant.key.getTargetPx()));
        }
    }

    private static void validateFontBytes(byte[] fontData, String logicalName, CgFontStyle style) {
        if (fontData == null || fontData.length == 0) {
            throw new IllegalArgumentException("fontData must not be null or empty");
        }
        if (logicalName == null) {
            throw new IllegalArgumentException("logicalName must not be null");
        }
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
    }

    private static void destroyQuietly(HBFont hbFont, FTFace face, FreeTypeLibrary library) {
        if (hbFont != null) {
            try {
                hbFont.destroy();
            } catch (Exception ignored) {
            }
        }
        if (face != null) {
            try {
                face.destroy();
            } catch (Exception ignored) {
            }
        }
        if (library != null) {
            try {
                library.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readFileBytes(String path) throws IOException {
        InputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void applyVariationsToFace(FTFace face, List<CgFontVariation> variations) {
        if (variations == null || variations.isEmpty()) {
            return;
        }
        face.setVariationCoordinates(toVariationTags(variations), toVariationValues(variations));
    }

    private static void applyVariationsToHbFont(HBFont hbFont, List<CgFontVariation> variations) {
        if (variations == null || variations.isEmpty()) {
            return;
        }
        hbFont.setVariations(toVariationTags(variations), toVariationValues(variations));
    }

    private static void applyVariationsToMsdfFont(FreeTypeMSDFIntegration.Font font,
                                                  List<CgFontVariation> variations) {
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

    private static CgFontMetrics extractMetrics(FTFace face, int targetPx) {
        int unitsPerEM = face.getUnitsPerEM();
        float scale = (float) targetPx / unitsPerEM;

        float ascender = face.getAscender() * scale;
        float descender = Math.abs(face.getDescender() * scale);
        float faceHeight = face.getHeight() * scale;
        float lineGap = faceHeight - ascender - descender;
        if (lineGap < 0) {
            lineGap = 0;
        }
        float lineHeight = ascender + descender + lineGap;

        float xHeight = measureGlyphHeight(face, 'x', scale);
        float capHeight = measureGlyphHeight(face, 'H', scale);
        if (xHeight <= 0) {
            xHeight = ascender * 0.5f;
        }
        if (capHeight <= 0) {
            capHeight = ascender * 0.7f;
        }

        return new CgFontMetrics(ascender, descender, lineGap, lineHeight, xHeight, capHeight);
    }

    private static float measureGlyphHeight(FTFace face, int charCode, float scale) {
        try {
            int glyphIndex = face.getCharIndex(charCode);
            if (glyphIndex == 0) {
                return 0;
            }
            face.loadGlyph(glyphIndex, FTLoadFlags.FT_LOAD_DEFAULT);
            return face.getGlyphMetrics().getHoriBearingY() / 64.0f;
        } catch (FreeTypeException e) {
            return 0;
        }
    }

    private static List<CgFontAxisInfo> extractVariationAxes(FTFace face) {
        FTVariationAxisInfo[] axes = face.getVariationAxes();
        if (axes == null || axes.length == 0) {
            return Collections.emptyList();
        }
        List<CgFontAxisInfo> mapped = new ArrayList<CgFontAxisInfo>(axes.length);
        for (FTVariationAxisInfo axis : axes) {
            mapped.add(new CgFontAxisInfo(
                    axis.getTag(),
                    axis.getName(),
                    axis.getMinValue(),
                    axis.getDefaultValue(),
                    axis.getMaxValue()));
        }
        return Collections.unmodifiableList(mapped);
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("CgFont has been disposed: " + logicalName);
        }
    }

    private void requireSizeBound(String message) {
        if (!sizeBound) {
            throw new IllegalStateException(message);
        }
    }

    @Override
    public String toString() {
        return "CgFont{logicalName=" + logicalName
                + ", style=" + style
                + ", sizeBound=" + sizeBound
                + ", targetPx=" + (sizeBound ? key.getTargetPx() : "unsized")
                + ", disposed=" + disposed + "}";
    }

    private static final class LoadedNativeState {
        private final FreeTypeLibrary ftLibrary;
        private final FTFace ftFace;
        private final HBFont hbFont;
        private final List<CgFontAxisInfo> variationAxes;
        private final CgFontMetrics metrics;

        private LoadedNativeState(FreeTypeLibrary ftLibrary,
                                  FTFace ftFace,
                                  HBFont hbFont,
                                  List<CgFontAxisInfo> variationAxes,
                                  CgFontMetrics metrics) {
            this.ftLibrary = ftLibrary;
            this.ftFace = ftFace;
            this.hbFont = hbFont;
            this.variationAxes = variationAxes;
            this.metrics = metrics;
        }
    }
}
