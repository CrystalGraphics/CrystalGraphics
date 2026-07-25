package com.crystalgraphics.gl.texture;


import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import com.crystalgraphics.util.io.CgTextureIO;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;

/**
 * 2D-array GL texture (target {@code GL_TEXTURE_2D_ARRAY = 0x8C1A}). Single
 * concrete impl of {@link CgTexture} for 2D-arrays.
 *
 * <p>Each "layer" of the array is a 2D image of identical width/height. All
 * layers share the same pixel format, mipmap config, and sampler params.
 * Storage is allocated via {@link GL12#glTexImage3D} and per-layer upload
 * happens via {@link GL12#glTexSubImage3D}.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(String...)} / {@link #create(CgTextureSpec, String...)} — cached via
 *       {@link CgTextureManager}; supports in-place {@link #reload()}.</li>
 *   <li>{@link #createDirect(CgTextureSpec, String...)} — bypass cache; no reload support.</li>
 * </ul>
 */
public final class CgTexture2DArray extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2DArray.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D_ARRAY = 0x8C1A;

    // ── Type-specific state ─────────────────────────────────────────
    @Getter private final int depth;

    /** Source paths for reload; {@code null} for createDirect (no reload support). */
    @Getter private final String[] sourcePaths;

    private CgTexture2DArray(int textureId, int width, int height, int depth, CgTextureSpec spec, String[] sourcePaths) {
        super(textureId, width, height, spec);
        this.depth = depth;
        this.sourcePaths = sourcePaths;
    }

    // ── Factories ────────────────────────────────────────────────────

    /** Creates a 2D-array texture from image paths using {@link CgTextureSpec#RGBA8_LINEAR}, cached. */
    public static CgTexture2DArray create(String... paths) {
        return create(CgTextureSpec.RGBA8_LINEAR, paths);
    }

    /**
     * Creates a 2D-array texture from a list of image paths, cached.
     * Subsequent calls with the same paths return the cached instance.
     *
     * @throws IllegalArgumentException if {@code paths} is empty, any layer
     *         fails to load, or layers have mismatching dimensions
     */
    public static CgTexture2DArray create(CgTextureSpec spec, String... paths) {
        String key = String.join(CgTextureManager.PATH_SEPARATOR, paths);
        CgTexture result = CgTextureManager.get().getOrCreate(key, () -> doCreate(spec, paths, paths));
        return result != null ? (CgTexture2DArray) result : null;
    }

    /**
     * Creates a fresh 2D-array texture without consulting the cache.
     * Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * No reload support.
     */
    public static CgTexture2DArray createDirect(CgTextureSpec spec, String... paths) {
        return doCreate(spec, paths, null);
    }

    /**
     * Allocates an empty {@code layers}-deep array texture with no initial pixel
     * data — one {@code glTexImage3D} call with a {@code null} data pointer,
     * orphaning/reserving storage for every layer up front. Pairs with
     * {@link #uploadLayerRegion} to push data into individual layers afterward.
     *
     * <p>Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * No reload support (there is no source path to reload from). This is the
     * "N-layer atlas built up incrementally over many frames" entry point, as
     * opposed to {@link #create}/{@link #createDirect}'s "N whole images,
     * uploaded once" model.</p>
     *
     * @param width  layer width in pixels (must be positive)
     * @param height layer height in pixels (must be positive)
     * @param layers number of layers to reserve (must be positive; fixed for
     *               the lifetime of this texture — growing it requires a new
     *               texture and copying every existing layer)
     * @param spec   format/filter/wrap; {@link CgTextureSpec#getGlType()} is used
     *               only for this initial allocation call (no real data is
     *               transferred with a null pointer) — {@link #uploadLayerRegion}
     *               takes its own explicit format/type per call, which does not
     *               need to match this spec's type (e.g. uploading {@code float}
     *               data into an {@code RGBA16F}-internal-format array, whose
     *               spec type may be {@code GL_HALF_FLOAT}).
     */
    public static CgTexture2DArray allocateEmpty(int width, int height, int layers, CgTextureSpec spec) {
        if (width <= 0 || height <= 0) 
            throw new IllegalArgumentException("width/height must be positive, got: " + width + "x" + height);
        if (layers <= 0) 
            throw new IllegalArgumentException("layers must be positive, got: " + layers);
        
        int id = CgGL.glGenTextures();
        CgTexture2DArray tex = new CgTexture2DArray(id, width, height, layers, spec, null);
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    spec.getGlInternalFormat(), width, height, layers, 0,
                    spec.getGlBaseFormat(), spec.getGlType(), (ByteBuffer) null);
            spec.applyTo(GL_TEXTURE_2D_ARRAY);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
        return tex;
    }

    // ── Per-layer sub-rect upload ───────────────────────────────────────

    /**
     * Uploads pixel data into a sub-rectangle of a single layer, via
     * {@code glTexSubImage3D}. Pairs with {@link #allocateEmpty}.
     *
     * <p>{@code format}/{@code type} describe {@code data}'s actual layout and
     * are supplied explicitly by the caller rather than derived from this
     * texture's spec — the spec describes GPU-side storage, not what a given
     * upload call's CPU-side data looks like (e.g. this array's internal
     * format may be {@code RGBA16F} while a caller uploads plain {@code float}
     * data via {@code GL_FLOAT}, letting the driver quantize on upload).</p>
     *
     * @param layer  target layer index, {@code [0, getDepth())}
     * @param x      sub-rectangle left edge within the layer, in pixels
     * @param y      sub-rectangle top edge within the layer, in pixels
     * @param w      sub-rectangle width, in pixels
     * @param h      sub-rectangle height, in pixels
     * @param format GL pixel format of {@code data} (e.g. {@code GL_RED}, {@code GL_RGBA})
     * @param type   GL pixel type of {@code data} (e.g. {@code GL_UNSIGNED_BYTE}, {@code GL_FLOAT})
     * @param data   tightly-packed pixel data, {@code w * h * channels(format)} elements
     */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h,
                                   int format, int type, ByteBuffer data) {
        checkNotDeleted();
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        // GL defaults GL_UNPACK_ALIGNMENT to 4 (rows padded to a 4-byte boundary in the
        // client buffer). data here is tightly packed with no such padding, so any
        // single-byte-per-pixel upload (e.g. R8 bitmap glyphs) whose row width isn't a
        // multiple of 4 gets every row after the first misread — the driver skips/shifts
        // bytes trying to find each row's "padded" start, corrupting the uploaded pixels.
        // Must be set to 1 for the sub-image call itself, then restored.
        int prevAlignment = CgGL.glGetInteger(CgGL.GL_UNPACK_ALIGNMENT);
        CgGL.glPixelStorei(CgGL.GL_UNPACK_ALIGNMENT, 1);
        try {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glPixelStorei(CgGL.GL_UNPACK_ALIGNMENT, prevAlignment);
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    /** {@code float}-data variant of {@link #uploadLayerRegion(int, int, int, int, int, int, int, ByteBuffer)}. */
    public void uploadLayerRegion(int layer, int x, int y, int w, int h,
                                   int format, int type, FloatBuffer data) {
        checkNotDeleted();
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, x, y, layer, w, h, 1, format, type, data);
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Re-uploads all layers from pre-loaded image data in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     * Updates {@link #getWidth()} / {@link #getHeight()} to match the new images.
     *
     * <p>The images array must have exactly {@link #getDepth()} entries, all
     * the same width/height. Validation is the caller's responsibility.</p>
     */
    public void upload(CgImageData[] images) {
        checkNotDeleted();
        int w = images[0].width();
        int h = images[0].height();
        int uploadPixelFormat = pixelFormatForChannels(images[0].channels());
        CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    spec.getGlInternalFormat(), w, h, images.length, 0,
                    uploadPixelFormat, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            for (int i = 0; i < images.length; i++) {
                CgGL.glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                        0, 0, i, w, h, 1,
                        uploadPixelFormat, GL_UNSIGNED_BYTE, images[i].pixels());
            }
            spec.applyTo(GL_TEXTURE_2D_ARRAY);
            
            this.width = w;
            this.height = h;
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    // ── Reload ────────────────────────────────────────────────────────

    @Override
    public void reload() {
        if (sourcePaths == null) return;
        try {
            upload(loadAndValidate(sourcePaths));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CgTexture2DArray] Failed to reload layers", e);
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_2D_ARRAY; }

    // ── Internal factory ──────────────────────────────────────────────

    private static CgTexture2DArray doCreate(CgTextureSpec spec, String[] paths, String[] sourcePaths) {
        CgImageData[] images = loadAndValidate(paths);
        int id = CgGL.glGenTextures();
        CgTexture2DArray tex = new CgTexture2DArray(id, images[0].width(), images[0].height(), paths.length, spec, sourcePaths);
        try {
            tex.upload(images);
            return tex;
        } catch (RuntimeException e) {
            tex.delete();
            throw e;
        }
    }

    private static CgImageData[] loadAndValidate(String[] paths) {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("paths must not be empty");
        }
        CgImageData[] images = new CgImageData[paths.length];
        for (int i = 0; i < paths.length; i++) {
            images[i] = CgTextureIO.load(paths[i]);
            if (images[i] == null) {
                throw new IllegalArgumentException("Failed to load layer " + i + ": " + paths[i]);
            }
        }
        int w = images[0].width(), h = images[0].height();
        for (int i = 1; i < images.length; i++) {
            if (images[i].width() != w || images[i].height() != h) {
                throw new IllegalArgumentException("All layers must be same size. Layer 0: " + w + "x" + h
                        + ", layer " + i + ": " + images[i].width() + "x" + images[i].height());
            }
        }
        return images;
    }
}
