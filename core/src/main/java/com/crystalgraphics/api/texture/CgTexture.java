package com.crystalgraphics.api.texture;


import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Common abstraction over an owned GL texture object.
 *
 * <p>Implementations wrap a single GL texture (2D, 2D-array, 3D, etc.) and own
 * its lifecycle. The texture id is allocated at creation time and released by
 * {@link #delete()}. After deletion, the instance is unusable and any binding
 * call will throw {@link IllegalStateException}.</p>
 *
 * <h3>Targets</h3>
 * <p>The {@link #getTarget()} method returns the GL texture target for binding:
 * {@code GL_TEXTURE_2D = 0x0DE1}, {@code GL_TEXTURE_3D = 0x806F},
 * {@code GL_TEXTURE_2D_ARRAY = 0x8C1A}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. All operations must occur on the GL context thread.</p>
 */
public interface CgTexture {

    /**
     * Binds this texture to its native GL target on the currently active
     * texture unit (does not change the active unit).
     */
    void bind();

    /**
     * Activates {@code GL_TEXTURE0 + unit} and binds this texture to its
     * native GL target. Equivalent to a {@code glActiveTexture} +
     * {@code glBindTexture} pair.
     *
     * @param unit zero-based texture unit (0 corresponds to GL_TEXTURE0)
     */
    void bind(int unit);

    /** @return the OpenGL texture object id */
    int getId();

    /** @return texture width in pixels (level-0) */
    int getWidth();

    /** @return texture height in pixels (level-0) */
    int getHeight();

    /**
     * @return the GL texture target constant
     *         (e.g. {@code GL_TEXTURE_2D}, {@code GL_TEXTURE_3D},
     *         {@code GL_TEXTURE_2D_ARRAY})
     */
    int getTarget();

    /** @return {@code true} if {@link #delete()} has been called */
    boolean isDeleted();

    /**
     * Whether its images would not load, so it samples a stand-in: the purple and black checkerboard for a 2D texture,
     * clear for a missing layer. Loads a texture from files if it has not yet.
     *
     * <pre>{@code
     * CgTexture2D icon = CgTextures.of("mymod:textures/gui/icon.png");
     * if (icon.isMissing()) drawPlaceholder(); else draw(icon);
     * }</pre>
     */
    default boolean isMissing() {
        return false;
    }

    /**
     * Releases the underlying GL texture object. Idempotent — a second call
     * is a silent no-op (matching {@code glDeleteTextures(0)} semantics).
     */
    void delete();

    /**
     * Decodes its images again and uploads them in place: same object, same id. A file that will not load keeps what
     * it had. Nothing for a texture made without files ({@code createEmpty}, {@code createFromPixels}), or one from
     * files not yet used. Called by {@link CgTextureManager#reloadAll()} on a resource reload.
     */
    default void reload() {}

    /** The mip levels its storage holds: 1 for none. Level {@code l} is {@code max(1, size >> l)} on each axis. */
    default int getLevels() {
        return 1;
    }

    /** Levels in a full mip chain of a {@code width} by {@code height} texture, down to 1x1. */
    static int fullChain(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
    }

    // ── Static helpers ────────────────────────────────────────────────────

    /** Sets the GL texture state
     * @param target to set (GL_TEXTURE_2D, GL_TEXTURE_2D_ARRAY, GL_TEXTURE_3D, GL_TEXTURE_CUBEMAP)
     * @param textureId
     */
    static void bind(int target, int textureId) {
        CgGL.glBindTexture(target, textureId);
    }

    /**
     * @param unit texture unit to set active. (0-31, depends on hardware)
     */
    static void active(int unit) {
        // Normalize the range to 0-31, if fed raw unit value
        if (unit > CgGL.GL_TEXTURE0) unit -= CgGL.GL_TEXTURE0;
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
    }
}