package io.github.somehussar.crystalgraphics.gl.texture;

import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * </ul>
 */
public final class CgTexture3D extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture3D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_3D = 0x806F;

    // ── Type-specific state ─────────────────────────────────────────
    @Getter private final int depth;

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
        GL11.glBindTexture(GL_TEXTURE_3D, textureId);
        try {
            GL12.glTexImage3D(GL_TEXTURE_3D, 0,
                    spec.getFormat().getInternalFormat(), w, h, images.length, 0,
                    uploadPixelFormat, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            for (int i = 0; i < images.length; i++) {
                GL12.glTexSubImage3D(GL_TEXTURE_3D, 0,
                        0, 0, i, w, h, 1,
                        uploadPixelFormat, GL_UNSIGNED_BYTE, images[i].pixels());
            }
            spec.applyTo(GL_TEXTURE_3D);

            this.width = w;
            this.height = h;
        } finally {
            GL11.glBindTexture(GL_TEXTURE_3D, 0);
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
        int id = GL11.glGenTextures();
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
