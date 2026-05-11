package io.github.somehussar.crystalgraphics.gl.texture;

import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 2D GL texture (target {@code GL_TEXTURE_2D}). The single concrete impl of
 * {@link CgTexture} for 2D textures — owns one GL texture id, allocated by the
 * static factories and released by {@link #delete()}.
 *
 * <p>Failure during creation is always failure-atomic: the partially-allocated
 * GL id is deleted before re-throwing, so no ids are ever leaked.</p>
 *
 * <h3>Caching</h3>
 * <p>{@link #create(String)} and {@link #create(String, CgTextureSpec)} are
 * transparently cached via {@link CgTextureManager}. First caller wins;
 * subsequent callers with the same path get the cached instance.</p>
 *
 * <h3>Reload</h3>
 * <p>Path-based textures support in-place {@link #reload()}: the image is
 * re-uploaded into the same GL texture id so existing references remain valid.
 * Procedural textures ({@code createEmpty}, {@code createFromPixels}) have no
 * source path and silently no-op on reload.</p>
 *
 * <h3>Upload</h3>
 * <p>Two {@code upload()} instance methods allow callers to push fresh pixel
 * data into a live texture without re-allocating the GL object:
 * {@link #upload(CgImageData)} for path-loaded images and
 * {@link #upload(int, int, ByteBuffer, int, int)} for raw pixel data.</p>
 */
public final class CgTexture2D extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** Asset path this texture was loaded from; {@code null} for procedural textures. */
    @Getter private final String sourcePath;

    private CgTexture2D(int textureId, int width, int height, CgTextureSpec spec, String sourcePath) {
        super(textureId, width, height, spec);
        this.sourcePath = sourcePath;
    }

    // ── Factories ────────────────────────────────────────────────────

    /** Loads a 2D texture from an asset path using {@link CgTextureSpec#RGBA8_LINEAR}, cached. */
    public static CgTexture2D create(String path) {
        return CgTextureManager.get().getOrCreate(path);
    }

    /**
     * Loads a 2D texture from an asset path with a custom spec, cached.
     * On a cache hit the cached texture is returned regardless of {@code spec} — first caller wins.
     *
     * @return the texture, or the manager fallback if loading failed
     */
    public static CgTexture2D create(String path, CgTextureSpec spec) {
        return CgTextureManager.get().getOrCreate(path, spec);
    }

    /**
     * Creates a fresh texture from {@code path} without consulting the cache.
     * Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * Returns {@code null} if loading fails.
     */
    public static CgTexture2D createDirect(String path, CgTextureSpec spec) {
        CgImageData data = CgTextureIO.load(path);
        if (data == null) return null;
        return doCreate(data.width(), data.height(), data.pixels(),
                pixelFormatForChannels(data.channels()), GL_UNSIGNED_BYTE, spec, null);
    }

    /** Creates an empty 2D texture with no image data. Not cached; caller owns the lifecycle. */
    public static CgTexture2D createEmpty(int width, int height, CgTextureSpec spec) {
        return doCreate(width, height, null,
                spec.getGlBaseFormat(), spec.getGlType(), spec, null);
    }

    /**
     * Creates a 2D texture from a raw RGBA {@link ByteBuffer}.
     * Not cached; caller owns the lifecycle. Use for procedural/dynamic textures.
     */
    public static CgTexture2D createFromPixels(int width, int height, ByteBuffer pixels, CgTextureSpec spec) {
        return doCreate(width, height, pixels,
                spec.getGlBaseFormat(), spec.getGlType(), spec, null);
    }

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Re-uploads pixel data from a decoded image in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     * Updates {@link #getWidth()} / {@link #getHeight()} to match the new image.
     *
     * <p>Use {@link CgTextureIO#load(String)} to obtain a {@link CgImageData}.</p>
     */
    public void upload(CgImageData image) {
        checkNotDeleted();
        GL11.glBindTexture(GL_TEXTURE_2D, textureId);
        try {
            GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                    spec.getGlInternalFormat(), image.width(), image.height(), 0,
                    pixelFormatForChannels(image.channels()), GL_UNSIGNED_BYTE, image.pixels());
            spec.applyTo(GL_TEXTURE_2D);
         
            this.width = image.width();
            this.height = image.height();
        } finally {
            GL11.glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    /**
     * Re-uploads raw pixel data in-place.
     * Also reapplies the spec's filter/wrap params and regenerates mipmaps if enabled.
     * Updates {@link #getWidth()} / {@link #getHeight()} to match the new dimensions.
     *
     * <p>Use this overload for procedural textures that generate pixels directly into a
     * {@link ByteBuffer} rather than loading from an asset path.</p>
     *
     * @param pixelFormat upload pixel format (e.g. {@code GL_RGBA}, {@code GL_RED})
     * @param pixelType   upload pixel type (e.g. {@code GL_UNSIGNED_BYTE})
     */
    public void upload(int width, int height, ByteBuffer pixels, int pixelFormat, int pixelType) {
        checkNotDeleted();
        GL11.glBindTexture(GL_TEXTURE_2D, textureId);
        try {
            GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                    spec.getGlInternalFormat(), width, height, 0,
                    pixelFormat, pixelType, pixels);
            spec.applyTo(GL_TEXTURE_2D);
          
            this.width = width;
            this.height = height;
        } finally {
            GL11.glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    // ── Reload ────────────────────────────────────────────────────────

    @Override
    public void reload() {
        if (sourcePath == null) return;

        CgImageData data = CgTextureIO.load(sourcePath);
        if (data == null) {
            LOGGER.log(Level.WARNING, "[CgTexture2D] Failed to load image data {0}", sourcePath);
            return;
        }
        try {
            upload(data);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CgTexture2D] Exception reloading " + sourcePath, e);
        }
    }

    @Override public int getTarget() { return GL_TEXTURE_2D; }

    // ── Internal factory ──────────────────────────────────────────────

    /**
     * The single GL-allocation path. Generates a texture id, constructs the object,
     * calls upload, and returns. Cleans up the id on any exception (failure-atomic).
     */
    private static CgTexture2D doCreate(int width, int height, ByteBuffer pixels,
                                        int pixelFormat, int pixelType,
                                        CgTextureSpec spec, String sourcePath) {
        int id = GL11.glGenTextures();
        CgTexture2D tex = new CgTexture2D(id, width, height, spec, sourcePath);
        try {
            tex.upload(width, height, pixels, pixelFormat, pixelType);
            return tex;
        } catch (RuntimeException e) {
            GL11.glDeleteTextures(id);
            throw e;
        }
    }
}
