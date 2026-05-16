package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.util.io.CgTextureIO;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cubemap GL texture (target {@code CgGL.GL_TEXTURE_CUBE_MAP = 0x8513}). Single
 * concrete impl of {@link CgTexture} for cubemaps.
 *
 * <p>A cubemap is six square 2D images uploaded to six discrete face targets
 * ({@code CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X} through
 * {@code CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z}, GL constants 0x8515..0x851A). All
 * faces must be the same size and share the same pixel format, mipmap config,
 * and sampler params.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(CgTextureSpec, String, String, String, String, String, String)} — cached via
 *       {@link CgTextureManager}; supports in-place {@link #reload()}.</li>
 *   <li>{@link #createDirect(CgTextureSpec, String, String, String, String, String, String)} — bypass cache; no reload support.</li>
 *   <li>{@link #createEmpty(int, CgTextureSpec)} — empty faces; caller owns lifecycle.</li>
 * </ul>
 */
public final class CgTextureCubemap extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTextureCubemap.class.getName());
    
    /** Six face targets in canonical order: +X, -X, +Y, -Y, +Z, -Z. */
    private static final int[] FACE_TARGETS = {
            CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X, CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
            CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
            CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
    };

    /** Source face paths for reload; {@code null} for createDirect and createEmpty. */
    @Getter private final String[] sourcePaths;

    private CgTextureCubemap(int textureId, int size, CgTextureSpec spec, String[] sourcePaths) {
        super(textureId, size, size, spec);
        this.sourcePaths = sourcePaths;
    }

    // ── Factories ────────────────────────────────────────────────────

    /**
     * Creates a cubemap from six face image paths, cached.
     * Subsequent calls with the same six paths return the cached instance.
     *
     * @throws IllegalArgumentException if any path fails to load, or faces
     *         have mismatching dimensions, or any face is non-square
     */
    public static CgTextureCubemap create(CgTextureSpec spec,
                                          String posX, String negX,
                                          String posY, String negY,
                                          String posZ, String negZ) {
        String[] paths = { posX, negX, posY, negY, posZ, negZ };
        String key = String.join(CgTextureManager.PATH_SEPARATOR, paths);
        CgTexture result = CgTextureManager.get().getOrCreate(key, () -> doCreate(spec, paths, paths));
        return result != null ? (CgTextureCubemap) result : null;
    }

    /**
     * Creates a fresh cubemap without consulting the cache.
     * Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * No reload support.
     */
    public static CgTextureCubemap createDirect(CgTextureSpec spec,
                                                String posX, String negX,
                                                String posY, String negY,
                                                String posZ, String negZ) {
        String[] paths = { posX, negX, posY, negY, posZ, negZ };
        return doCreate(spec, paths, null);
    }

    /** Creates an empty cubemap with no image data. Not cached; caller owns the lifecycle. */
    public static CgTextureCubemap createEmpty(int size, CgTextureSpec spec) {
        if (size <= 0) throw new IllegalArgumentException("Cubemap size must be positive, got: " + size);
        int id = CgGL.glGenTextures();
        CgTextureCubemap tex = new CgTextureCubemap(id, size, spec, null);
        try {
            CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, id);
            try {
                int internalFormat = spec.getGlInternalFormat();
                int pf = spec.getGlBaseFormat();
                int pt = spec.getGlType();
                for (int face : FACE_TARGETS) {
                    CgGL.glTexImage2D(face, 0, internalFormat, size, size, 0, pf, pt, (ByteBuffer) null);
                }
                spec.applyTo(CgGL.GL_TEXTURE_CUBE_MAP);
             
            } finally {
                CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, 0);
            }
            return tex;
        } catch (RuntimeException e) {
            CgGL.glDeleteTextures(id);
            throw e;
        }
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Re-uploads all six faces from pre-loaded image data in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     *
     * <p>The array must contain exactly 6 images in canonical order
     * (+X, -X, +Y, -Y, +Z, -Z), all the same square size.
     * Use {@link #loadFaces(String[])} to load and validate before calling.</p>
     */
    public void upload(CgImageData[] faces) {
        checkNotDeleted();
        int size = faces[0].width();
        int uploadPixelFormat = pixelFormatForChannels(faces[0].channels());
        CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, textureId);
        try {
            int internalFormat = spec.getGlInternalFormat();
            for (int i = 0; i < 6; i++) {
                CgGL.glTexImage2D(FACE_TARGETS[i], 0, internalFormat, size, size, 0,
                        uploadPixelFormat, GL_UNSIGNED_BYTE, faces[i].pixels());
            }
            
            spec.applyTo(CgGL.GL_TEXTURE_CUBE_MAP);
            
            this.width = size;
            this.height = size;
        } finally {
            CgGL.glBindTexture(CgGL.GL_TEXTURE_CUBE_MAP, 0);
        }
    }

    // ── Reload ────────────────────────────────────────────────────────

    @Override
    public void reload() {
        if (sourcePaths == null) return;
        try {
            upload(loadFaces(sourcePaths));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CgTextureCubemap] Failed to reload faces", e);
        }
    }

    @Override public int getTarget() { return CgGL.GL_TEXTURE_CUBE_MAP; }

    // ── Internal factory ──────────────────────────────────────────────

    private static CgTextureCubemap doCreate(CgTextureSpec spec, String[] paths, String[] sourcePaths) {
        CgImageData[] faces = loadFaces(paths);
        int id = CgGL.glGenTextures();
        CgTextureCubemap tex = new CgTextureCubemap(id, faces[0].width(), spec, sourcePaths);
        try {
            tex.upload(faces);
            return tex;
        } catch (RuntimeException e) {
            tex.delete();
            throw e;
        }
    }

    private static CgImageData[] loadFaces(String[] paths) {
        CgImageData[] images = new CgImageData[6];
        for (int i = 0; i < 6; i++) {
            images[i] = CgTextureIO.load(paths[i]);
            if (images[i] == null) {
                throw new IllegalArgumentException("Failed to load cubemap face " + i + ": " + paths[i]);
            }
        }
        int size = images[0].width();
        if (images[0].height() != size) {
            throw new IllegalArgumentException("Cubemap face 0 must be square. Got: "
                    + size + "x" + images[0].height());
        }
        for (int i = 1; i < 6; i++) {
            if (images[i].width() != size || images[i].height() != size) {
                throw new IllegalArgumentException("All cubemap faces must be the same square size ("
                        + size + "x" + size + "). Face " + i + " is "
                        + images[i].width() + "x" + images[i].height());
            }
        }
        return images;
    }
}
