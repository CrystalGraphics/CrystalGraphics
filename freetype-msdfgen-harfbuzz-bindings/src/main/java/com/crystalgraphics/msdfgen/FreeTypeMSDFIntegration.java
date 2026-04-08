package com.crystalgraphics.msdfgen;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/**
 * High-level API for loading font glyphs via FreeType and generating MSDF bitmaps.
 * <p>
 * Requires the native library to be compiled with FreeType support
 * ({@code -DMSDFGEN_USE_FREETYPE=ON}). Call {@link #isAvailable()} to check.
 * <p>
 * Usage:
 * <pre>{@code
 * FreeTypeMSDFIntegration ft = FreeTypeMSDFIntegration.create();
 * try {
 *     FreeTypeMSDFIntegration.Font font = ft.loadFont("path/to/font.ttf");
 *     try {
 *         FreeTypeMSDFIntegration.GlyphData glyph = font.loadGlyph('A');
 *         MSDFShape shape = glyph.getShape();
 *         // ... generate SDF from shape ...
 *         shape.free();
 *     } finally {
 *         font.destroy();
 *     }
 * } finally {
 *     ft.destroy();
 * }
 * }</pre>
 */
public final class FreeTypeMSDFIntegration {

    /** No coordinate scaling applied to glyph shapes. */
    public static final int FONT_SCALING_NONE = 0;
    /** Glyph coordinates normalized to EM square (0..1 range). */
    public static final int FONT_SCALING_EM_NORMALIZED = 1;
    /** Legacy coordinate scaling (msdfgen 1.x compatible). */
    public static final int FONT_SCALING_LEGACY = 2;

    private long ftHandle;
    private boolean destroyed;

    private FreeTypeMSDFIntegration(long ftHandle) {
        this.ftHandle = ftHandle;
        this.destroyed = false;
    }

    /**
     * Returns true if the native library was compiled with FreeType support.
     */
    public static boolean isAvailable() {
        try {
            return MSDFNative.nHasFreetypeSupport();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Creates a new FreeType integration instance.
     *
     * @throws MSDFException if FreeType is not available or initialization fails
     */
    public static FreeTypeMSDFIntegration create() {
        if (!isAvailable()) {
            throw new MSDFException("FreeType support is not available. "
                + "The native library must be compiled with -DMSDFGEN_USE_FREETYPE=ON");
        }
        long[] handleOut = new long[1];
        MSDFResult.check(MSDFNative.nFreetypeInit(handleOut));
        if (handleOut[0] == 0) {
            throw new MSDFException("FreeType initialization returned null handle");
        }
        return new FreeTypeMSDFIntegration(handleOut[0]);
    }

    /**
     * Loads a font from a file path.
     *
     * @param path path to the font file (TTF, OTF, WOFF, etc.)
     * @return a Font handle (must be destroyed when done)
     * @throws MSDFException if the font cannot be loaded
     */
    public Font loadFont(String path) {
        checkNotDestroyed();
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Font path must not be null or empty");
        }
        long[] fontHandleOut = new long[1];
        int result = MSDFNative.nLoadFont(ftHandle, path, fontHandleOut);
        if (result != MSDFResult.SUCCESS || fontHandleOut[0] == 0) {
            throw new MSDFException("Failed to load font: " + path);
        }
        return new Font(ftHandle, fontHandleOut[0]);
    }

    /**
     * Loads a font from a File object.
     *
     * @param file the font file
     * @return a Font handle (must be destroyed when done)
     * @throws MSDFException if the font cannot be loaded
     * @throws IOException if the file doesn't exist
     */
    public Font loadFont(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Font file must not be null");
        }
        if (!file.exists()) {
            throw new IOException("Font file does not exist: " + file.getAbsolutePath());
        }
        return loadFont(file.getAbsolutePath());
    }

    /**
     * Loads a font from raw byte data (e.g., embedded resource).
     *
     * @param data raw font file bytes
     * @return a Font handle (must be destroyed when done)
     * @throws MSDFException if the font cannot be loaded
     */
    public Font loadFontData(byte[] data) {
        checkNotDestroyed();
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Font data must not be null or empty");
        }
        long[] fontHandleOut = new long[1];
        int result = MSDFNative.nLoadFontData(ftHandle, data, data.length, fontHandleOut);
        if (result != MSDFResult.SUCCESS || fontHandleOut[0] == 0) {
            throw new MSDFException("Failed to load font from byte data");
        }
        return new Font(ftHandle, fontHandleOut[0]);
    }

    /**
     * Loads a font from a classpath resource via InputStream.
     *
     * @param stream input stream containing font data
     * @return a Font handle (must be destroyed when done)
     * @throws IOException if reading the stream fails
     * @throws MSDFException if the font data cannot be parsed
     */
    public Font loadFont(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("Input stream must not be null");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return loadFontData(baos.toByteArray());
    }

    /**
     * Destroys this FreeType instance and frees all associated resources.
     * All fonts loaded from this instance should be destroyed first.
     */
    public void destroy() {
        if (!destroyed) {
            MSDFNative.nFreetypeDeinit(ftHandle);
            destroyed = true;
            ftHandle = 0;
        }
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    private void checkNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("FreeTypeMSDFIntegration has been destroyed");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (!destroyed) {
            destroy();
        }
        super.finalize();
    }

    /**
     * Represents a loaded font. Provides methods to load glyph shapes and query metrics.
     */
    public static final class Font {

        private final long ftHandle;
        private long fontHandle;
        private boolean destroyed;

        Font(long ftHandle, long fontHandle) {
            this.ftHandle = ftHandle;
            this.fontHandle = fontHandle;
            this.destroyed = false;
        }

        public void setVariations(String[] axisTags, float[] values) {
            checkNotDestroyed();
            if (axisTags == null || values == null) {
                throw new IllegalArgumentException("axisTags and values must not be null");
            }
            if (axisTags.length != values.length) {
                throw new IllegalArgumentException("axisTags and values length must match");
            }
            if (axisTags.length == 0) {
                return;
            }
            try {
                MSDFResult.check(MSDFNative.nSetFontVariations(ftHandle, fontHandle, axisTags, values, values.length));
            } catch (UnsatisfiedLinkError e) {
                throw new UnsupportedOperationException("Variable-font MSDF extraction requires updated native bindings", e);
            }
        }

        /**
         * Loads a glyph shape by Unicode codepoint with EM-normalized coordinates.
         *
         * @param codepoint the Unicode codepoint (e.g., 'A', 0x41)
         * @return glyph data including shape and advance width
         * @throws MSDFException if the glyph cannot be loaded
         */
        public GlyphData loadGlyph(int codepoint) {
            return loadGlyph(codepoint, FONT_SCALING_EM_NORMALIZED);
        }

        /**
         * Loads a glyph shape by Unicode codepoint with specified coordinate scaling.
         *
         * @param codepoint the Unicode codepoint
         * @param coordinateScaling one of FONT_SCALING_NONE, FONT_SCALING_EM_NORMALIZED, FONT_SCALING_LEGACY
         * @return glyph data including shape and advance width
         * @throws MSDFException if the glyph cannot be loaded
         */
        public GlyphData loadGlyph(int codepoint, int coordinateScaling) {
            checkNotDestroyed();
            long[] shapeOut = new long[1];
            double[] advanceOut = new double[1];
            int result = MSDFNative.nLoadGlyph(fontHandle, codepoint, coordinateScaling,
                advanceOut, shapeOut);
            if (result != MSDFResult.SUCCESS || shapeOut[0] == 0) {
                throw new MSDFException("Failed to load glyph for codepoint U+"
                    + Integer.toHexString(codepoint).toUpperCase());
            }
            return new GlyphData(new MSDFShape(shapeOut[0]), advanceOut[0]);
        }

        /**
         * Loads a glyph shape by glyph index with EM-normalized coordinates.
         *
         * @param glyphIndex the glyph index (use {@link #getGlyphIndex} to resolve from codepoint)
         * @return glyph data including shape and advance width
         * @throws MSDFException if the glyph cannot be loaded
         */
        public GlyphData loadGlyphByIndex(int glyphIndex) {
            return loadGlyphByIndex(glyphIndex, FONT_SCALING_EM_NORMALIZED);
        }

        /**
         * Loads a glyph shape by glyph index with specified coordinate scaling.
         *
         * @param glyphIndex the glyph index
         * @param coordinateScaling coordinate scaling mode
         * @return glyph data including shape and advance width
         * @throws MSDFException if the glyph cannot be loaded
         */
        public GlyphData loadGlyphByIndex(int glyphIndex, int coordinateScaling) {
            checkNotDestroyed();
            long[] shapeOut = new long[1];
            double[] advanceOut = new double[1];
            int result = MSDFNative.nLoadGlyphByIndex(fontHandle, glyphIndex, coordinateScaling,
                advanceOut, shapeOut);
            if (result != MSDFResult.SUCCESS || shapeOut[0] == 0) {
                throw new MSDFException("Failed to load glyph at index " + glyphIndex);
            }
            return new GlyphData(new MSDFShape(shapeOut[0]), advanceOut[0]);
        }

        /**
         * Resolves a Unicode codepoint to a glyph index.
         *
         * @param codepoint the Unicode codepoint
         * @return the glyph index
         * @throws MSDFException if the codepoint cannot be resolved
         */
        public int getGlyphIndex(int codepoint) {
            checkNotDestroyed();
            int[] indexOut = new int[1];
            MSDFResult.check(MSDFNative.nGetGlyphIndex(fontHandle, codepoint, indexOut));
            return indexOut[0];
        }

        /**
         * Gets kerning between two Unicode codepoints.
         *
         * @param cp1 left codepoint
         * @param cp2 right codepoint
         * @return the kerning value (in font units or EM-normalized, depending on font)
         * @throws MSDFException if kerning cannot be retrieved
         */
        public double getKerning(int cp1, int cp2) {
            checkNotDestroyed();
            double[] kerningOut = new double[1];
            MSDFResult.check(MSDFNative.nGetKerning(fontHandle, cp1, cp2, kerningOut));
            return kerningOut[0];
        }

        /**
         * Gets kerning between two glyph indices.
         *
         * @param index1 left glyph index
         * @param index2 right glyph index
         * @return the kerning value
         * @throws MSDFException if kerning cannot be retrieved
         */
        public double getKerningByIndex(int index1, int index2) {
            checkNotDestroyed();
            double[] kerningOut = new double[1];
            MSDFResult.check(MSDFNative.nGetKerningByIndex(fontHandle, index1, index2, kerningOut));
            return kerningOut[0];
        }

        /**
         * Destroys this font and frees its resources.
         */
        public void destroy() {
            if (!destroyed) {
                MSDFNative.nDestroyFont(fontHandle);
                destroyed = true;
                fontHandle = 0;
            }
        }

        public boolean isDestroyed() {
            return destroyed;
        }

        private void checkNotDestroyed() {
            if (destroyed) {
                throw new IllegalStateException("Font has been destroyed");
            }
        }

        @Override
        protected void finalize() throws Throwable {
            if (!destroyed) {
                destroy();
            }
            super.finalize();
        }
    }

    /**
     * Holds the result of loading a glyph: the vector shape and the advance width.
     * The caller is responsible for freeing the shape when done.
     */
    public static final class GlyphData {

        private final MSDFShape shape;
        private final double advance;

        GlyphData(MSDFShape shape, double advance) {
            this.shape = shape;
            this.advance = advance;
        }

        /** Returns the glyph's vector shape. Caller must call {@link MSDFShape#free()} when done. */
        public MSDFShape getShape() {
            return shape;
        }

        /** Returns the glyph's horizontal advance width. */
        public double getAdvance() {
            return advance;
        }
    }
}
