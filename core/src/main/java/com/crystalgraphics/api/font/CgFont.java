package com.crystalgraphics.api.font;

import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTVariationAxisInfo;
import com.crystalgraphics.freetype.FreeTypeException;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import com.crystalgraphics.text.font.Sfnt;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;

import java.nio.file.Path;
import java.nio.file.Paths;
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
 *       is the renderable/shapable form used by {@link com.crystalgraphics.text.layout.CgTextLayoutEngine} and
 *       {@code CgTextRenderer}.</li>
 * </ul>
 *
 * <p>This preserves the existing atlas/shaping architecture, which still requires a
 * concrete pixel size internally, while letting callers treat the uploaded font data
 * as a reusable logical font asset.</p>
 *
 * <pre>{@code
 * CgFont inter = CgFont.load("assets/fonts/Inter-Regular.ttf", CgFontStyle.REGULAR).atSize(16);
 *
 * // one face of a collection: MS Gothic is face 0 of msgothic.ttc, MS UI Gothic face 1
 * CgFont uiGothic = CgFont.load("C:/Windows/Fonts/msgothic.ttc", 1, CgFontStyle.REGULAR, 16);
 * }</pre>
 *
 * <p>A font loaded from a path is opened from its file by every native that reads it. One loaded
 * from bytes goes into native memory once, a collection face copied out first, so
 * {@link #getFontBytes()} is always one font; {@link #getFaceIndex()} and the key remember which
 * face it was. See {@link #getData()}.</p>
 */
public class CgFont {

    private static final Logger LOGGER = Logger.getLogger(CgFont.class.getName());

    private final String logicalName;
    private final int faceIndex;
    private final CgFontStyle style;
    private final List<CgFontVariation> variations;
    private final CgFontData data;
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
                   int faceIndex,
                   CgFontStyle style,
                   CgFontData data,
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
        this.faceIndex = faceIndex;
        this.style = style;
        this.data = data;
        this.variations = variations;
        this.sizeBound = sizeBound;
        this.key = sizeBound ? new CgFontKey(logicalName, faceIndex, style, targetPx.intValue(), variations) : null;
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
        return loadUnsized(fileOf(fontPath, 0), fontPath, 0, style, variations);
    }

    public static CgFont load(String fontPath, CgFontStyle style, int targetPx) {
        return load(fontPath, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(String fontPath,
                              CgFontStyle style,
                              int targetPx,
                              List<CgFontVariation> variations) {
        return loadSized(fileOf(fontPath, 0), fontPath, 0, style, targetPx, variations, null);
    }

    /**
     * Face {@code faceIndex} of a font file on disk, size-bound.
     *
     * <pre>{@code
     * CgFont uiGothic = CgFont.load("C:/Windows/Fonts/msgothic.ttc", 1, CgFontStyle.REGULAR, 16);
     * }</pre>
     *
     * @throws IllegalArgumentException if the file is not a collection and {@code faceIndex != 0},
     *                                  or the collection has no such face
     */
    public static CgFont load(String fontPath, int faceIndex, CgFontStyle style, int targetPx) {
        return loadSized(fileOf(fontPath, faceIndex), fontPath, faceIndex, style, targetPx,
                Collections.<CgFontVariation>emptyList(), null);
    }

    public static CgFont load(byte[] fontData, String logicalName, CgFontStyle style) {
        return load(fontData, logicalName, style, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(byte[] fontData,
                              String logicalName,
                              CgFontStyle style,
                              List<CgFontVariation> variations) {
        return load(fontData, logicalName, 0, style, variations);
    }

    public static CgFont load(byte[] fontData, String logicalName,
                              CgFontStyle style, int targetPx) {
        return load(fontData, logicalName, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(byte[] fontData, String logicalName,
                              CgFontStyle style, int targetPx,
                              List<CgFontVariation> variations) {
        return load(fontData, logicalName, 0, style, targetPx, variations);
    }

    /**
     * Face {@code faceIndex} of {@code fontData}, unsized. {@code fontData} is the whole file — a
     * collection or a single font — and the key records {@code (logicalName, faceIndex)}.
     */
    public static CgFont load(byte[] fontData, String logicalName, int faceIndex,
                              CgFontStyle style, List<CgFontVariation> variations) {
        return loadUnsized(faceOf(fontData, logicalName, faceIndex, style),
                logicalName, faceIndex, style, variations);
    }

    public static CgFont load(byte[] fontData, String logicalName, int faceIndex,
                              CgFontStyle style, int targetPx) {
        return load(fontData, logicalName, faceIndex, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(byte[] fontData, String logicalName, int faceIndex,
                              CgFontStyle style, int targetPx, List<CgFontVariation> variations) {
        return loadSized(faceOf(fontData, logicalName, faceIndex, style),
                logicalName, faceIndex, style, targetPx, variations, null);
    }

    /** Face {@code faceIndex} of an installed font file, unsized, at {@code variations}. For {@link CgSystemFonts}. */
    static CgFont loadInstalledFace(Path file, int faceIndex, String logicalName,
                                    CgFontStyle style, List<CgFontVariation> variations) {
        return loadUnsized(CgFontData.ofFile(file, faceIndex), logicalName, faceIndex, style, variations);
    }

    private static CgFontData faceOf(byte[] fontData, String logicalName, int faceIndex, CgFontStyle style) {
        validateFontBytes(fontData, logicalName, style);
        return CgFontData.ofBytes(Sfnt.extractFace(fontData, faceIndex));
    }

    private static CgFontData fileOf(String fontPath, int faceIndex) {
        if (fontPath == null) {
            throw new IllegalArgumentException("fontPath must not be null");
        }
        return CgFontData.ofFile(Paths.get(fontPath).toAbsolutePath(), faceIndex);
    }

    private static CgFont loadUnsized(CgFontData data,
                                      String logicalName,
                                      int faceIndex,
                                      CgFontStyle style,
                                      List<CgFontVariation> variations) {
        validate(data, logicalName, style);
        List<CgFontVariation> canonicalVariations = CgFontKey.canonicalizeVariations(variations);
        LoadedNativeState state = loadNativeState(data, canonicalVariations, null, false);
        return new CgFont(logicalName, faceIndex, style, data, canonicalVariations,
                false, null, null, state.variationAxes,
                state.ftLibrary, state.ftFace, null, null);
    }

    private static CgFont loadSized(CgFontData data,
                                    String logicalName,
                                    int faceIndex,
                                    CgFontStyle style,
                                    int targetPx,
                                    List<CgFontVariation> variations,
                                    CgFont baseFont) {
        validate(data, logicalName, style);
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        List<CgFontVariation> canonicalVariations = CgFontKey.canonicalizeVariations(variations);
        LoadedNativeState state = loadNativeState(data, canonicalVariations, Integer.valueOf(targetPx), true);
        return new CgFont(logicalName, faceIndex, style, data, canonicalVariations,
                true, Integer.valueOf(targetPx), state.metrics, state.variationAxes,
                state.ftLibrary, state.ftFace, state.hbFont, baseFont);
    }

    /**
     * The single chokepoint every {@code load(...)} overload funnels through, which is why the
     * profiling scope lives here rather than on the public entry points.
     *
     * <p>Font loading is a startup cost that had never been measured — it creates a FreeType
     * library and face, optionally an HarfBuzz font, and parses the whole font file. Cheap per call
     * but called once per font per size, and entirely on whichever thread asked.
     */
    private static LoadedNativeState loadNativeState(CgFontData data,
                                                     List<CgFontVariation> variations,
                                                     Integer targetPx,
                                                     boolean createHbFont) {
        try (CgProfiler.Scope ignored = CgProfiler.scope("font.loadNative")) {
            CgProfiler.count("font.loadNative.count");
            if (data.file() == null) {
                CgProfiler.sample("font.loadNative.bytes", data.bytes().length);
            }
            return loadNativeStateInternal(data, variations, targetPx, createHbFont);
        }
    }

    private static LoadedNativeState loadNativeStateInternal(CgFontData data,
                                                             List<CgFontVariation> variations,
                                                             Integer targetPx,
                                                             boolean createHbFont) {
        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = null;
        HBFont hbFont = null;
        try {
            face = data.openFace(ftLib);
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

    /** Which face of the file this is; 0 unless it was loaded out of a collection. */
    public int getFaceIndex() {
        return faceIndex;
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

        // Locked: one base font backs families on every thread that shapes text.
        synchronized (sizedVariants) {
            Integer cacheKey = Integer.valueOf(targetPx);
            CgFont cached = sizedVariants.get(cacheKey);
            if (cached != null && !cached.isDisposed()) {
                return cached;
            }
            CgFont sized = loadSized(data, logicalName, faceIndex, style, targetPx, variations, this);
            sizedVariants.put(cacheKey, sized);
            return sized;
        }
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

    /**
     * Sparse cmap cache, one lazily-allocated 256-entry block per codepoint block.
     *
     * <p>{@link #getGlyphIndex} is a JNI call into FreeType, and font-family resolution calls it
     * <em>per character of every string shaped</em> to decide which face covers each cluster.
     * Measured on a 1000-label UI, that made font resolution 18% of all shaping time — for an
     * answer that is a property of the font file and can never change.
     *
     * <p>Entries store {@code glyphIndex + 1} so that the array's natural zero-fill means
     * "not yet looked up". That is what makes this safe to publish without synchronisation: a
     * thread seeing a partially-populated block simply recomputes the entries it finds as zero,
     * and {@code int} writes are atomic, so the worst case is a duplicated lookup rather than a
     * wrong answer. Storing the raw index would make 0 ambiguous between "uncached" and the
     * legitimate "no glyph" result, and a racing reader could then report a covered character as
     * uncovered.
     */
    private final int[][] glyphIndexCache = new int[(Character.MAX_CODE_POINT >> 8) + 1][];

    public int getGlyphIndex(int codePoint) {
        checkNotDisposed();
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
            return 0;
        }
        if (ftFace == null) {
            return 0;
        }

        int blockIndex = codePoint >>> 8;
        int[] block = glyphIndexCache[blockIndex];
        if (block == null) {
            block = new int[256];
            glyphIndexCache[blockIndex] = block;
        }

        int slot = codePoint & 0xFF;
        int cached = block[slot];
        if (cached != 0) {
            return cached - 1;
        }

        int index = ftFace.getCharIndex(codePoint);
        block[slot] = index + 1;
        return index;
    }

    /**
     * The font as standalone bytes. An installed font is opened from its file and holds none until
     * asked; the first call reads the file.
     */
    public byte[] getFontBytes() {
        checkNotDisposed();
        return data.bytes();
    }

    /** Where this font's data lives — one object shared by every size of it. @see CgFontData */
    public CgFontData getData() {
        checkNotDisposed();
        return data;
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

        if (sizedVariants != null) {
            List<CgFont> variants;
            synchronized (sizedVariants) {
                variants = new ArrayList<CgFont>(sizedVariants.values());
                sizedVariants.clear();
            }
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
            msdfFtFont = data.openMsdfFont(msdfFtInstance);
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
        synchronized (sizedVariants) {
            CgFont cached = sizedVariants.get(Integer.valueOf(variant.key.getTargetPx()));
            if (cached == variant) {
                sizedVariants.remove(Integer.valueOf(variant.key.getTargetPx()));
            }
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

    private static void validate(CgFontData data, String logicalName, CgFontStyle style) {
        if (data == null) {
            throw new IllegalArgumentException("font data must not be null");
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
                + (faceIndex != 0 ? ", faceIndex=" + faceIndex : "")
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
