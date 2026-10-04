package com.crystalgraphics.gl.texture;


import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;
import com.crystalgraphics.util.io.CgTextureIO;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;

/**
 * 3D GL texture (target {@code GL_TEXTURE_3D = 0x806F}). Single concrete impl
 * of {@link CgTexture} for 3D textures.
 *
 * <p>Each "slice" along the R axis is a 2D image of identical width/height.
 * Unlike a 2D-array, slices can be filtered linearly across the depth axis,
 * making 3D textures useful for volumetric data and 3D LUTs.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(String...)} / {@link #create(CgTextureSpec, String...)} — cached via
 *       {@link CgTextureManager}; supports in-place {@link #reload()}.</li>
 *   <li>{@link #createDirect(CgTextureSpec, String...)} — bypass cache; no reload support.</li>
 *   <li>{@link #createEmpty(int, int, int, CgTextureSpec, int)} — storage a kernel, a bake or an upload fills; any
 *       thread, its GL work waiting for the render thread as a {@link CgTexture2D}'s does.</li>
 * </ul>
 *
 * <pre>{@code
 * // a tiling noise volume baked on a worker: level 0 uploaded, the rest generated
 * CgTextureSpec repeat = CgTextureSpec.builder().type(CgTextureType.RGBA8).minFilter(GL_LINEAR).magFilter(GL_LINEAR)
 *         .wrapS(GL_REPEAT).wrapT(GL_REPEAT).wrapR(GL_REPEAT).build();
 * CgTexture3D noise = CgTexture3D.createEmpty(128, 128, 128, repeat, CgTexture3D.fullChain(128, 128, 128));
 * noise.uploadRegion(0, 0, 0, 0, 128, 128, 128, texels, GL_RGBA, GL_UNSIGNED_BYTE);
 * noise.generateMipmaps();
 * }</pre>
 */
public final class CgTexture3D extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture3D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_3D = 0x806F;

    // ── Type-specific state ─────────────────────────────────────────
    @Getter private final int depth;

    /** Levels allocated by {@link #createEmpty}; a spec that generates mipmaps has its full chain instead. */
    private int levels = 1;

    /** Source paths for reload; {@code null} for createDirect (no reload support). */
    @Getter private final String[] sourcePaths;

    private CgTexture3D(int textureId, int width, int height, int depth, CgTextureSpec spec, String[] sourcePaths) {
        super(textureId, width, height, spec);
        this.depth = depth;
        this.sourcePaths = sourcePaths;
    }

    // ── Factories ────────────────────────────────────────────────────

    /** Creates a 3D texture from slice paths using {@link CgTextureSpec#RGBA8_LINEAR}, cached. */
    public static CgTexture3D create(String... paths) {
        return create(CgTextureSpec.RGBA8_LINEAR, paths);
    }

    /**
     * Creates a 3D texture from a list of slice paths, cached.
     * Subsequent calls with the same paths return the cached instance.
     *
     * @throws IllegalArgumentException if {@code paths} is empty, any slice
     *         fails to load, or slices have mismatching dimensions
     */
    public static CgTexture3D create(CgTextureSpec spec, String... paths) {
        String key = String.join(CgTextureManager.PATH_SEPARATOR, paths);
        CgTexture result = CgTextureManager.get().getOrCreate(key, () -> doCreate(spec, paths, paths));
        return result != null ? (CgTexture3D) result : null;
    }

    /**
     * Creates a fresh 3D texture without consulting the cache.
     * Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * No reload support.
     */
    public static CgTexture3D createDirect(CgTextureSpec spec, String... paths) {
        return doCreate(spec, paths, null);
    }

    /** An empty texture of one level. Not cached; caller owns the lifecycle. */
    public static CgTexture3D createEmpty(int width, int height, int depth, CgTextureSpec spec) {
        return createEmpty(width, height, depth, spec, 1);
    }

    /**
     * An empty texture of {@code levels} mip levels, sampled through all of them. Any thread. Not cached; caller owns the
     * lifecycle.
     *
     * <ul>
     *   <li>Its minification filter becomes the spec's, mipmapped: trilinear from {@code GL_LINEAR}.</li>
     *   <li>A spec that generates its mipmaps ({@code CgMipmapConfig}) is refused: fill level 0, then
     *       {@link #generateMipmaps()}.</li>
     * </ul>
     */
    public static CgTexture3D createEmpty(int width, int height, int depth, CgTextureSpec spec, int levels) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("a 3D texture of " + width + "x" + height + "x" + depth);
        }
        if (levels < 1 || levels > fullChain(width, height, depth)) {
            throw new IllegalArgumentException(levels + " levels for a " + width + "x" + height + "x" + depth
                    + " texture: it holds 1 to " + fullChain(width, height, depth));
        }
        if (spec.getMipmaps().isEnabled()) {
            throw new IllegalArgumentException("a spec that generates mipmaps would generate them from nothing: "
                    + "give levels, fill level 0, then generateMipmaps()");
        }
        CgTexture3D tex = new CgTexture3D(0, width, height, depth, spec, null);
        tex.levels = levels;
        tex.gpu.run(() -> {
            int id = CgGL.glGenTextures();
            tex.textureId = id;
            try {
                tex.allocate();
            } catch (RuntimeException e) {
                CgGL.glDeleteTextures(id);
                tex.textureId = 0;
                throw e;
            }
        });
        return tex;
    }

    /** Levels in a full mip chain of a {@code width} x {@code height} x {@code depth} texture, down to 1x1x1. */
    public static int fullChain(int width, int height, int depth) {
        return CgTexture.fullChain(Math.max(width, depth), height);
    }

    private void allocate() {
        CgGL.glBindTexture(GL_TEXTURE_3D, textureId);
        try {
            for (int l = 0; l < levels; l++) {
                CgGL.glTexImage3D(GL_TEXTURE_3D, l, spec.getGlInternalFormat(), Math.max(1, width >> l),
                        Math.max(1, height >> l), Math.max(1, depth >> l), 0, spec.getGlBaseFormat(), spec.getGlType(),
                        (ByteBuffer) null);
            }
            spec.applyTo(GL_TEXTURE_3D);
            CgGL.glTexParameteri(GL_TEXTURE_3D, CgGL.GL_TEXTURE_MAX_LEVEL, levels - 1);
            if (levels > 1) {
                CgGL.glTexParameteri(GL_TEXTURE_3D, CgGL.GL_TEXTURE_MIN_FILTER, spec.getMinFilter() == CgGL.GL_NEAREST
                        ? CgGL.GL_NEAREST_MIPMAP_NEAREST : CgGL.GL_LINEAR_MIPMAP_LINEAR);
            }
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_3D, 0);
        }
    }

    /**
     * Writes a {@code width} x {@code height} x {@code depth} box at {@code (x, y, z)} of level {@code level}: slices
     * from {@code z} up, each its rows bottom first, tightly packed, in {@code pixelFormat} and {@code pixelType}. The
     * rest keeps what it held. Any thread; {@code pixels} is copied where the work waits.
     *
     * <pre>{@code
     * window.uploadRegion(0, 0, 0, 32, 128, 96, 16, slab, GL_RED_INTEGER, GL_UNSIGNED_BYTE);   // slices 32 to 47
     * }</pre>
     */
    public void uploadRegion(int level, int x, int y, int z, int width, int height, int depth, ByteBuffer pixels,
                             int pixelFormat, int pixelType) {
        checkNotDeleted();
        gpu.run(pixels, data -> {
            CgGL.glBindTexture(GL_TEXTURE_3D, textureId);
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                CgGL.glTexSubImage3D(GL_TEXTURE_3D, level, x, y, z, width, height, depth, pixelFormat, pixelType, data);
            } finally {
                CgGL.glBindTexture(GL_TEXTURE_3D, 0);
            }
        });
    }

    /** Fills every level below 0 from level 0, box-filtered by the driver. Any thread, after level 0 is written. */
    public void generateMipmaps() {
        checkNotDeleted();
        gpu.run(() -> {
            CgGL.glBindTexture(GL_TEXTURE_3D, textureId);
            try {
                CgGL.glGenerateMipmap(GL_TEXTURE_3D);
            } finally {
                CgGL.glBindTexture(GL_TEXTURE_3D, 0);
            }
        });
    }

    @Override
    public int getLevels() {
        return spec.getMipmaps().isEnabled() ? fullChain(width, height, depth) : levels;
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Re-uploads all slices from pre-loaded image data in-place.
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
        CgGL.glBindTexture(GL_TEXTURE_3D, textureId);
        try {
            CgGL.glTexImage3D(GL_TEXTURE_3D, 0,
                    spec.getGlInternalFormat(), w, h, images.length, 0,
                    uploadPixelFormat, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                for (int i = 0; i < images.length; i++) {
                    CgGL.glTexSubImage3D(GL_TEXTURE_3D, 0,
                            0, 0, i, w, h, 1,
                            uploadPixelFormat, GL_UNSIGNED_BYTE, images[i].pixels());
                }
            }
            spec.applyTo(GL_TEXTURE_3D);

            this.width = w;
            this.height = h;
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_3D, 0);
        }
    }

    // ── Reload ────────────────────────────────────────────────────────

    @Override
    public void reload() {
        if (sourcePaths == null) return;
        try {
            upload(loadAndValidate(sourcePaths));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CgTexture3D] Failed to reload slices", e);
        }
    }

    @Override
    public int getTarget() {return GL_TEXTURE_3D;}

    // ── Internal factory ──────────────────────────────────────────────

    private static CgTexture3D doCreate(CgTextureSpec spec, String[] paths, String[] sourcePaths) {
        CgImageData[] images = loadAndValidate(paths);
        int id = CgGL.glGenTextures();
        CgTexture3D tex = new CgTexture3D(id, images[0].width(), images[0].height(), paths.length, spec, sourcePaths);
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
                throw new IllegalArgumentException("Failed to load slice " + i + ": " + paths[i]);
            }
        }
        int w = images[0].width(), h = images[0].height();
        for (int i = 1; i < images.length; i++) {
            if (images[i].width() != w || images[i].height() != h) {
                throw new IllegalArgumentException("All slices must be same size. Slice 0: " + w + "x" + h
                        + ", slice " + i + ": " + images[i].width() + "x" + images[i].height());
            }
        }
        return images;
    }
}
