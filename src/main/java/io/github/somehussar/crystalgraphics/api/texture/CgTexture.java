package io.github.somehussar.crystalgraphics.api.texture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

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
     * Generates a full mipmap chain for this texture using
     * {@code glGenerateMipmap}. If GL30 is unavailable on the current
     * context, this is a no-op with a one-time warning logged.
     */
    void generateMipmaps();

    /**
     * Releases the underlying GL texture object. Idempotent — a second call
     * is a silent no-op (matching {@code glDeleteTextures(0)} semantics).
     */
    void delete();

    // ── Static helpers ────────────────────────────────────────────────────

    /** @return the normalized ID of the active texture unit, between 0-31.
     *  Highly discouraged against, as state querying the GPU via glGet* stalls the CPU-GPU pipeline. */
    static int getActiveUnit() {
        return GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
    }

    /** Sets the GL texture state
     * @param target to set (GL_TEXTURE_2D, GL_TEXTURE_2D_ARRAY, GL_TEXTURE_3D, GL_TEXTURE_CUBEMAP)
     * @param textureId
     */
    static void bind(int target, int textureId) {
        GL11.glBindTexture(target, textureId);
    }

    /**
     * @param unit texture unit to set active. (0-31, depends on hardware)
     */
    static void active(int unit) {
        // Normalize the range to 0-31, if fed raw unit value
        if (unit > GL13.GL_TEXTURE0) unit -= GL13.GL_TEXTURE0;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
    }
}