package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.util.io.CgTextureIO;
import com.crystalgraphics.util.io.CgTextureIO.CgImageData;

import lombok.Getter;

import javax.annotation.Nullable;
import com.crystalgraphics.platform.gl.CgGL;

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
public final class CgTexture2D extends CgTextureAbstract implements CgTextureUploads.Pending {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** Asset path this texture was loaded from; {@code null} for procedural textures. */
    @Getter private final String sourcePath;

    /** Pixels waiting for their GL object, made on the render thread; null once it exists. */
    private record Upload(int width, int height, ByteBuffer pixels, int format, int type) {}

    @Nullable
    private volatile Upload pending;

    private CgTexture2D(int textureId, int width, int height, CgTextureSpec spec, String sourcePath) {
        super(textureId, width, height, spec);
        this.sourcePath = sourcePath;
    }

    private CgTexture2D(int textureId, int width, int height) {
        super(textureId, width, height, CgTextureSpec.RGBA8_LINEAR, false);
        this.sourcePath = null;
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

    /**
     * As {@link #createDirect}, from any thread: decodes {@code path} here, so its size is known at once, and makes its
     * GL object on the render thread — before the next frame executes, or at its first bind there.
     *
     * <pre>{@code
     * CgTexture2D sprite = CgTexture2D.createDeferred("mymod:textures/gui/atlas.png", CgTextureSpec.RGBA8_NEAREST);
     * float u = 16f / sprite.getWidth();         // known now
     * recording.bindings().begin().texture(0, sprite).end();   // bound when the frame executes
     * }</pre>
     *
     * <ul>
     *   <li>{@link #getId()} is 0 until the GL object exists; a recorded draw never asks for it.</li>
     *   <li>Not cached; the caller owns it, and {@link #delete()} before the upload cancels it.</li>
     * </ul>
     *
     * @return null if decoding fails
     */
    public static CgTexture2D createDeferred(String path, CgTextureSpec spec) {
        CgImageData data = CgTextureIO.load(path);
        if (data == null) return null;
        return deferred(data.width(), data.height(), data.pixels(), pixelFormatForChannels(data.channels()),
                GL_UNSIGNED_BYTE, spec, path);
    }

    /** As {@link #createFromPixels}, from any thread: the GL object is made on the render thread. */
    public static CgTexture2D createFromPixelsDeferred(int width, int height, ByteBuffer pixels, CgTextureSpec spec) {
        return deferred(width, height, pixels, spec.getGlBaseFormat(), spec.getGlType(), spec, null);
    }

    private static CgTexture2D deferred(int width, int height, ByteBuffer pixels, int format, int type,
                                        CgTextureSpec spec, @Nullable String sourcePath) {
        CgTexture2D texture = new CgTexture2D(0, width, height, spec, sourcePath);
        texture.pending = new Upload(width, height, pixels, format, type);
        CgTextureUploads.schedule(texture);
        return texture;
    }

    /** Makes the GL object and uploads the pixels waiting for it. Render thread. */
    @Override
    public void applyPending() {
        Upload upload = pending;
        if (upload == null || isDeleted()) return;
        pending = null;
        textureId = CgGL.glGenTextures();
        upload(upload.width, upload.height, upload.pixels, upload.format, upload.type);
    }

    /** A deferred texture's upload, now, when this thread may issue GL. */
    private void ensureUploaded() {
        if (pending != null && CgGL.mayIssueGl()) {
            CgTextureUploads.cancel(this);
            applyPending();
        }
    }

    @Override
    public void bind() {
        ensureUploaded();
        super.bind();
    }

    @Override
    public void bind(int unit) {
        ensureUploaded();
        super.bind(unit);
    }

    @Override
    public int getId() {
        ensureUploaded();
        return super.getId();
    }

    @Override
    public void delete() {
        if (pending != null) {
            CgTextureUploads.cancel(this);
            pending = null;
        }
        super.delete();
    }

    /**
     * Adopts a texture someone else owns, so the engine can sample it: a host's, through
     * {@link CgGL#importHostTexture}.
     *
     * <pre>{@code
     * CgTexture2D scene = CgTexture2D.wrap(CgGL.importHostTexture(hostTextureId), width, height);
     * scene.bind(unit);
     * scene.delete();   // forgets it; the host's texture is untouched
     * }</pre>
     *
     * <p>Uploads throw: its storage and parameters are its owner's. Not cached.</p>
     */
    public static CgTexture2D wrap(int textureId, int width, int height) {
        return new CgTexture2D(textureId, width, height);
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
        checkOwned();
        CgGL.glBindTexture(GL_TEXTURE_2D, textureId);
        try {
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                CgGL.glTexImage2D(GL_TEXTURE_2D, 0,
                        spec.getGlInternalFormat(), image.width(), image.height(), 0,
                        pixelFormatForChannels(image.channels()), GL_UNSIGNED_BYTE, image.pixels());
            }
            spec.applyTo(GL_TEXTURE_2D);
         
            this.width = image.width();
            this.height = image.height();
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D, 0);
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
        checkOwned();
        CgGL.glBindTexture(GL_TEXTURE_2D, textureId);
        try {
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                CgGL.glTexImage2D(GL_TEXTURE_2D, 0,
                        spec.getGlInternalFormat(), width, height, 0,
                        pixelFormat, pixelType, pixels);
            }
            spec.applyTo(GL_TEXTURE_2D);
          
            this.width = width;
            this.height = height;
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    // ── Reload ────────────────────────────────────────────────────────

    @Override
    public void reload() {
        if (sourcePath == null || pending != null) return;   // a deferred one uploads what it decoded

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
        int id = CgGL.glGenTextures();
        CgTexture2D tex = new CgTexture2D(id, width, height, spec, sourcePath);
        try {
            tex.upload(width, height, pixels, pixelFormat, pixelType);
            return tex;
        } catch (RuntimeException e) {
            CgGL.glDeleteTextures(id);
            throw e;
        }
    }
}
