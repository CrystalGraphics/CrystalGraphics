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
 *
 * <h3>Any thread</h3>
 * <p>Every factory and upload works anywhere. Where no GL may run — a recording, a worker — the GL work waits for the
 * render thread, before the next frame executes; the size is known at once, and {@link #getId()} is 0 until then
 * off the render thread. A recorded draw binds it when the frame executes.</p>
 *
 * <pre>{@code
 * CgTexture2D sprite = CgTexture2D.createDirect("mymod:textures/gui/atlas.png", CgTextureSpec.RGBA8_NEAREST);
 * float u = 16f / sprite.getWidth();                         // known now, on any thread
 * recording.bindings().begin().texture(0, sprite).end();     // bound when the frame executes
 * }</pre>
 */
public final class CgTexture2D extends CgTextureAbstract {

    private static final Logger LOGGER = Logger.getLogger(CgTexture2D.class.getName());

    // ── GL constants ────────────────────────────────────────────────
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** Asset path this texture was loaded from; {@code null} for procedural textures. */
    @Getter private final String sourcePath;

    /** Levels specified at allocation; a spec that generates mipmaps has its full chain instead. */
    private int levels = 1;

    /** Whether {@link #bindLevel} narrowed what it samples. Render thread. */
    private boolean pinned;

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
     * Creates a fresh texture from {@code path} without consulting the cache, decoding it here. Any thread.
     * Not registered with {@link CgTextureManager}; caller owns the lifecycle.
     * Returns {@code null} if loading fails.
     */
    public static CgTexture2D createDirect(String path, CgTextureSpec spec) {
        CgImageData data = CgTextureIO.load(path);
        if (data == null) return null;
        return doCreate(data.width(), data.height(), data.pixels(),
                pixelFormatForChannels(data.channels()), GL_UNSIGNED_BYTE, spec, path, 1);
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
        return createEmpty(width, height, spec, 1);
    }

    /**
     * An empty texture of {@code levels} mip levels, sampled through all of them: what a kernel or a pass fills level by
     * level. Not cached; caller owns the lifecycle.
     *
     * <pre>{@code
     * CgTexture2D chain = CgTexture2D.createEmpty(w, h, CgTextureSpec.RGBA16F_LINEAR, CgTexture.fullChain(w, h));
     * float level2 = textureLod(chain, uv, 2.0).r;   // in a shader, once something wrote level 2
     * }</pre>
     *
     * <ul>
     *   <li>Its minification filter becomes the spec's, mipmapped: trilinear from {@code GL_LINEAR}.</li>
     *   <li>A spec that generates its mipmaps ({@code CgMipmapConfig}) makes its own chain and is refused here.</li>
     * </ul>
     */
    public static CgTexture2D createEmpty(int width, int height, CgTextureSpec spec, int levels) {
        if (levels < 1 || levels > CgTexture.fullChain(width, height)) {
            throw new IllegalArgumentException(levels + " levels for a " + width + "x" + height + " texture: it holds 1 to "
                    + CgTexture.fullChain(width, height));
        }
        if (levels > 1 && spec.getMipmaps().isEnabled()) {
            throw new IllegalArgumentException("a spec that generates mipmaps makes its own chain: give levels 1");
        }
        return doCreate(width, height, null, spec.getGlBaseFormat(), spec.getGlType(), spec, null, levels);
    }

    /**
     * Creates a 2D texture from a raw RGBA {@link ByteBuffer}.
     * Not cached; caller owns the lifecycle. Use for procedural/dynamic textures.
     */
    public static CgTexture2D createFromPixels(int width, int height, ByteBuffer pixels, CgTextureSpec spec) {
        return doCreate(width, height, pixels,
                spec.getGlBaseFormat(), spec.getGlType(), spec, null, 1);
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
        upload(image.width(), image.height(), image.pixels(), pixelFormatForChannels(image.channels()),
                GL_UNSIGNED_BYTE);
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
        this.width = width;
        this.height = height;
        gpu.run(pixels, data -> texImage(width, height, data, pixelFormat, pixelType));
    }

    /**
     * Writes a {@code width} x {@code height} region at {@code (x, y)} of level {@code level}: rows bottom first, tightly
     * packed, in {@code pixelFormat} and {@code pixelType}. The rest of the texture keeps what it held.
     *
     * <pre>{@code
     * heights.uploadRegion(0, 32, 0, 16, 16, slab, GL_RED, GL_FLOAT);   // a 16x16 R32F slab at (32, 0)
     * }</pre>
     */
    public void uploadRegion(int level, int x, int y, int width, int height, ByteBuffer pixels, int pixelFormat,
                             int pixelType) {
        checkNotDeleted();
        gpu.run(pixels, data -> {
            CgGL.glBindTexture(GL_TEXTURE_2D, textureId);
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                CgGL.glTexSubImage2D(GL_TEXTURE_2D, level, x, y, width, height, pixelFormat, pixelType, data);
            } finally {
                CgGL.glBindTexture(GL_TEXTURE_2D, 0);
            }
        });
    }

    private void texImage(int width, int height, @Nullable ByteBuffer pixels, int pixelFormat, int pixelType) {
        CgGL.glBindTexture(GL_TEXTURE_2D, textureId);
        try {
            try (CgTightUnpack ignored = CgTightUnpack.begin()) {
                CgGL.glTexImage2D(GL_TEXTURE_2D, 0,
                        spec.getGlInternalFormat(), width, height, 0,
                        pixelFormat, pixelType, pixels);
            }
            for (int l = 1; l < levels; l++) {
                CgGL.glTexImage2D(GL_TEXTURE_2D, l, spec.getGlInternalFormat(), Math.max(1, width >> l),
                        Math.max(1, height >> l), 0, pixelFormat, pixelType, (ByteBuffer) null);
            }
            spec.applyTo(GL_TEXTURE_2D);
            if (levels > 1) {
                CgGL.glTexParameteri(GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAX_LEVEL, levels - 1);
                CgGL.glTexParameteri(GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, spec.getMinFilter() == CgGL.GL_NEAREST
                        ? CgGL.GL_NEAREST_MIPMAP_NEAREST : CgGL.GL_LINEAR_MIPMAP_LINEAR);
            }
        } finally {
            CgGL.glBindTexture(GL_TEXTURE_2D, 0);
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

    /** Binds it sampling every level, undoing a {@link #bindLevel}. */
    @Override
    public void bind() {
        super.bind();
        if (pinned) sampleLevels(0, getLevels() - 1, false);
    }

    /** Binds it at {@code unit} sampling every level, undoing a {@link #bindLevel}. */
    @Override
    public void bind(int unit) {
        super.bind(unit);
        if (pinned) sampleLevels(0, getLevels() - 1, false);
    }

    /**
     * Render thread: binds it at {@code unit} sampling level {@code level} alone, until it is next bound whole. What lets
     * a pass draw into one level while reading another without a feedback loop: GL's rule, and on Vulkan a view of the
     * one level. A shader reads the level as the texture's base, at LOD 0.
     *
     * <pre>{@code
     * chain.bindLevel(0, 2);            // a pass drawing into level 3 reads level 2
     * // ... draws ...
     * chain.unpinLevels();              // every level sampled again, bound on the active unit
     * }</pre>
     */
    public void bindLevel(int unit, int level) {
        CgTexture.active(unit);
        bindLevel(level);
    }

    /** {@link #bindLevel(int, int)} on the active unit. */
    public void bindLevel(int level) {
        if (level < 0 || level >= getLevels()) throw new IllegalArgumentException("level " + level + " of " + getLevels());
        super.bind();
        sampleLevels(level, level, true);
    }

    /** Render thread: after {@link #bindLevel}, binds it on the active unit sampling every level. Nothing when none is pinned. */
    public void unpinLevels() {
        if (pinned) bind();
    }

    private void sampleLevels(int base, int max, boolean pin) {
        CgGL.glTexParameteri(GL_TEXTURE_2D, CgGL.GL_TEXTURE_BASE_LEVEL, base);
        CgGL.glTexParameteri(GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAX_LEVEL, max);
        pinned = pin;
    }

    @Override public int getTarget() { return GL_TEXTURE_2D; }

    @Override
    public int getLevels() {
        return spec.getMipmaps().isEnabled() ? CgTexture.fullChain(width, height) : levels;
    }

    // ── Internal factory ──────────────────────────────────────────────

    /**
     * The single allocation path: the object now, its GL texture through {@link #gpu}. Failure-atomic: the id is deleted
     * if the first upload throws.
     */
    private static CgTexture2D doCreate(int width, int height, @Nullable ByteBuffer pixels,
                                        int pixelFormat, int pixelType,
                                        CgTextureSpec spec, @Nullable String sourcePath, int levels) {
        CgTexture2D tex = new CgTexture2D(0, width, height, spec, sourcePath);
        tex.levels = levels;
        tex.gpu.run(pixels, data -> {
            int id = CgGL.glGenTextures();
            tex.textureId = id;
            try {
                tex.texImage(width, height, data, pixelFormat, pixelType);
            } catch (RuntimeException e) {
                CgGL.glDeleteTextures(id);
                tex.textureId = 0;
                throw e;
            }
        });
        return tex;
    }
}
