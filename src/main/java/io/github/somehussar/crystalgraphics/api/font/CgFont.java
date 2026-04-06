package io.github.somehussar.crystalgraphics.api.font;

import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTVariationAxisInfo;
import com.crystalgraphics.freetype.FreeTypeException;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import com.crystalgraphics.harfbuzz.HBFont;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import com.msdfgen.FreeTypeIntegration;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Public API handle for a loaded font at a specific style and pixel size.
 *
 * <p>A {@code CgFont} manages dual FreeType lifecycles:</p>
 * <ul>
 *   <li><strong>Bitmap path</strong>: {@link FreeTypeLibrary} + {@link FTFace} for
 *       glyph rasterization (always loaded).</li>
 *   <li><strong>MSDF path</strong>: {@link FreeTypeIntegration} +
 *       {@link FreeTypeIntegration.Font} for shape extraction (lazy-loaded on first
 *       MSDF glyph request).</li>
 * </ul>
 *
 * <p>Both paths share the same raw font bytes, loaded once. The HarfBuzz font
 * ({@link HBFont}) is created via {@link FreeTypeHarfBuzzIntegration} from the
 * bitmap {@code FTFace}.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Call {@link #dispose()} to release all native resources. Disposal is
 * <strong>idempotent</strong> — calling it twice is safe. After disposal,
 * all native handles are invalidated and further use will throw.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used from the render thread.</p>
 *
 * @see CgFontKey
 * @see CgFontMetrics
 */
public class CgFont {

    private static final Logger LOGGER = Logger.getLogger(CgFont.class.getName());

    private final CgFontKey key;
    private final CgFontMetrics metrics;
    private final byte[] fontBytes;
    private final List<CgFontAxisInfo> variationAxes;

    // Bitmap path (always loaded)
    private FreeTypeLibrary ftLibrary;
    private FTFace ftFace;
    private HBFont hbFont;

    // MSDF path (lazy-loaded)
    private FreeTypeIntegration msdfFtInstance;
    private FreeTypeIntegration.Font msdfFtFont;

    private boolean disposed;

    private Runnable disposeListener;

    // ── Private constructor ────────────────────────────────────────────

    private CgFont(CgFontKey key, CgFontMetrics metrics, byte[] fontBytes,
                   List<CgFontAxisInfo> variationAxes,
                   FreeTypeLibrary ftLibrary, FTFace ftFace, HBFont hbFont) {
        this.key = key;
        this.metrics = metrics;
        this.fontBytes = fontBytes;
        this.variationAxes = variationAxes;
        this.ftLibrary = ftLibrary;
        this.ftFace = ftFace;
        this.hbFont = hbFont;
        this.disposed = false;
    }

    // ── Factory methods ───────────────────────────────────────────────

    /**
     * Loads a font from a file path. Reads font bytes, creates FTFace and HBFont.
     *
     * @param fontPath  absolute file path to .ttf or .otf
     * @param style     font style (must match a style in the file; REGULAR is safe default)
     * @param targetPx  render size in pixels
     * @return a new CgFont instance (never null)
     * @throws FreeTypeException     if FreeType cannot load the font
     * @throws IllegalArgumentException if fontPath is null or targetPx &lt;= 0
     */
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
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        byte[] data;
        try {
            data = readFileBytes(fontPath);
        } catch (IOException e) {
            throw new FreeTypeException(0, "Failed to read font file: " + fontPath + " — " + e.getMessage());
        }
        return loadFromBytes(data, fontPath, style, targetPx, variations);
    }

    /**
     * Loads a font from a byte array (for JAR-packed fonts).
     *
     * @param fontData    raw font file data
     * @param logicalName logical name for the font (used in CgFontKey and logging)
     * @param style       font style
     * @param targetPx    render size in pixels
     * @return a new CgFont instance (never null)
     * @throws FreeTypeException     if FreeType cannot load the font
     * @throws IllegalArgumentException if fontData is null/empty or targetPx &lt;= 0
     */
    public static CgFont load(byte[] fontData, String logicalName,
                              CgFontStyle style, int targetPx) {
        return load(fontData, logicalName, style, targetPx, Collections.<CgFontVariation>emptyList());
    }

    public static CgFont load(byte[] fontData, String logicalName,
                              CgFontStyle style, int targetPx,
                              List<CgFontVariation> variations) {
        if (fontData == null || fontData.length == 0) {
            throw new IllegalArgumentException("fontData must not be null or empty");
        }
        if (logicalName == null) {
            throw new IllegalArgumentException("logicalName must not be null");
        }
        if (targetPx <= 0) {
            throw new IllegalArgumentException("targetPx must be > 0, got: " + targetPx);
        }
        return loadFromBytes(fontData, logicalName, style, targetPx, variations);
    }

    // ── Core loading logic ────────────────────────────────────────────

    private static CgFont loadFromBytes(byte[] data, String fontId,
                                        CgFontStyle style, int targetPx,
                                        List<CgFontVariation> variations) {
        CgFontKey key = new CgFontKey(fontId, style, targetPx, variations);

        // 1. Create FreeType library + face for bitmap rasterization
        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face;
        try {
            face = ftLib.newFaceFromMemory(data, 0);
        } catch (FreeTypeException e) {
            ftLib.destroy();
            throw e;
        }

        // 2. Set pixel size
        try {
            applyVariationsToFace(face, key.getVariations());
            face.setPixelSizes(0, targetPx);
        } catch (FreeTypeException e) {
            face.destroy();
            ftLib.destroy();
            throw e;
        } catch (RuntimeException e) {
            face.destroy();
            ftLib.destroy();
            throw e;
        }

        List<CgFontAxisInfo> availableAxes = extractVariationAxes(face);

        // 3. Create HarfBuzz font from the FT face
        HBFont hbFont;
        try {
            hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
            applyVariationsToHbFont(hbFont, key.getVariations());
        } catch (RuntimeException e) {
            face.destroy();
            ftLib.destroy();
            throw new FreeTypeException(0,
                    "Failed to create HBFont from FTFace: " + e.getMessage());
        }

        // 4. Extract font metrics from FTFace
        CgFontMetrics metrics = extractMetrics(face, targetPx);

        return new CgFont(key, metrics, data, availableAxes, ftLib, face, hbFont);
    }

    /**
     * Extracts font-level metrics from the FTFace, scaled to the target pixel size.
     *
     * <p>FTFace.getAscender/getDescender/getHeight return values in font design units.
     * We scale them: {@code value * targetPx / unitsPerEM}.</p>
     */
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

        // xHeight and capHeight: attempt to measure from actual glyphs
        float xHeight = measureGlyphHeight(face, 'x', scale);
        float capHeight = measureGlyphHeight(face, 'H', scale);

        // Fallback estimates if glyphs are missing
        if (xHeight <= 0) {
            xHeight = ascender * 0.5f;
        }
        if (capHeight <= 0) {
            capHeight = ascender * 0.7f;
        }

        return new CgFontMetrics(ascender, descender, lineGap, lineHeight, xHeight, capHeight);
    }

    /**
     * Measures the bitmap height of a character by loading its glyph.
     * Returns 0 if the glyph cannot be loaded.
     */
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

    // ── Public API ────────────────────────────────────────────────────

    /** Returns the font key identifying this font registration. */
    public CgFontKey getKey() {
        return key;
    }

    /** Returns font-level metrics (ascender, descender, line height, etc.). */
    public CgFontMetrics getMetrics() {
        return metrics;
    }

    public List<CgFontVariation> getVariations() {
        return key.getVariations();
    }

    public List<CgFontAxisInfo> getVariationAxes() {
        return variationAxes;
    }

    public boolean isVariableFont() {
        return !variationAxes.isEmpty();
    }

    /**
     * Returns whether this font provides a non-zero glyph for the given code point.
     *
     * <p>This is the primitive used by the fallback resolver. It does not perform
     * shaping; it only asks FreeType whether a glyph exists for the code point.</p>
     */
    public boolean canDisplayCodePoint(int codePoint) {
        return getGlyphIndex(codePoint) != 0;
    }

    /**
     * Resolves a Unicode code point to the underlying FreeType glyph index.
     * Returns 0 when the font has no glyph for the requested code point.
     */
    public int getGlyphIndex(int codePoint) {
        checkNotDisposed();
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
            return 0;
        }
        return ftFace != null ? ftFace.getCharIndex(codePoint) : 0;
    }

    /** Returns the raw font bytes (shared between bitmap and MSDF paths). */
    public byte[] getFontBytes() {
        checkNotDisposed();
        return fontBytes;
    }

    /** Returns whether this font has been disposed. */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Registers a listener that will be called when this font is disposed.
     * Used by {@code CgFontRegistry} to trigger atlas cleanup automatically.
     *
     * @param listener the callback to invoke on dispose (before native cleanup)
     */
    public void setDisposeListener(Runnable listener) {
        this.disposeListener = listener;
    }

    /**
     * Releases all native resources (FTFace, HBFont, FreeType library handles,
     * MSDF FreeTypeIntegration if loaded). Idempotent — calling twice is safe.
     *
     * <p><strong>LIFO order</strong>: MSDF font → MSDF integration →
     * HBFont → FTFace → FreeTypeLibrary.</p>
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        // Notify listener (e.g., CgFontRegistry for atlas cleanup)
        if (disposeListener != null) {
            try {
                disposeListener.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in dispose listener", e);
            }
            disposeListener = null;
        }

        // MSDF path (if loaded) — LIFO: font first, then integration
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

        // Bitmap path — LIFO: HBFont → FTFace → FreeTypeLibrary
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

    // ── Package-private accessors (for CgTextLayoutBuilder in same package) ──

    /**
     * Returns the HarfBuzz font for text shaping.
     * Package-private — only accessible from {@code CgTextLayoutBuilder} in the
     * same package ({@code api/font}).
     */
    HBFont getHbFontInternal() {
        checkNotDisposed();
        return hbFont;
    }

    /**
     * Returns the FreeType face for bitmap rasterization.
     * Package-private — for use by {@code CgFontRegistry}.
     */
    public FTFace getFtFace() {
        checkNotDisposed();
        return ftFace;
    }

    /**
     * Restores the shared FreeType/HarfBuzz shaping state to this font's base target size.
     *
     * <p>The pose-aware raster path temporarily retunes the shared {@link FTFace}
     * to alternate effective sizes while building bitmap glyphs. Shaping still uses
     * the long-lived {@link HBFont}, so callers that mutate the face size must call
     * this method before any later layout pass relies on the shared shaping font.</p>
     */
    public void restoreBaseFontSizeForShaping() {
        checkNotDisposed();
        if (ftFace == null) {
            return;
        }
        ftFace.setPixelSizes(0, key.getTargetPx());
        if (hbFont != null && !hbFont.isDestroyed()) {
            FreeTypeHarfBuzzIntegration.syncFontMetrics(hbFont, ftFace);
        }
    }

    /**
     * Returns or lazily creates the MSDF FreeTypeIntegration.Font handle.
     *
     * <p>The MSDF font is only created when first needed. It uses a separate
     * {@link FreeTypeIntegration} instance from the bitmap {@link FreeTypeLibrary}
     * because they are different native libraries (msdfgen vs. freetype JNI).</p>
     *
     * @return the MSDF font handle, or null if MSDF is not available
     */
    public FreeTypeIntegration.Font getMsdfFont() {
        checkNotDisposed();
        if (msdfFtFont != null) {
            return msdfFtFont;
        }

        // Lazy init — check availability
        if (!FreeTypeIntegration.isAvailable()) {
            LOGGER.warning("MSDF FreeTypeIntegration is not available; "
                    + "MSDF generation will be skipped for font: " + key.getFontPath());
            return null;
        }

        try {
            msdfFtInstance = FreeTypeIntegration.create();
            msdfFtFont = msdfFtInstance.loadFontData(fontBytes);
            applyVariationsToMsdfFont(msdfFtFont, key.getVariations());
            return msdfFtFont;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to initialize MSDF font for: "
                    + key.getFontPath(), e);
            // Clean up partial init
            if (msdfFtFont != null) {
                try { msdfFtFont.destroy(); } catch (Exception ignored) { }
                msdfFtFont = null;
            }
            if (msdfFtInstance != null) {
                try { msdfFtInstance.destroy(); } catch (Exception ignored) { }
                msdfFtInstance = null;
            }
            return null;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

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

    private static void applyVariationsToMsdfFont(FreeTypeIntegration.Font font,
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
            throw new IllegalStateException("CgFont has been disposed: " + key);
        }
    }

    @Override
    public String toString() {
        return "CgFont{key=" + key + ", disposed=" + disposed + "}";
    }
}
