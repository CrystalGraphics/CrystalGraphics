package com.crystalgraphics.api.texture;


import javax.annotation.Nullable;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.asset.tree.CgBinaryAsset;
import com.crystalgraphics.gl.texture.CgTextureManager;

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
public interface CgTexture extends CgBinaryAsset {

    /**
     * Binds it at {@code unit} for the draw or dispatch being recorded: what that pass's device set reads there.
     *
     * <pre>{@code
     * albedo.bind(0);   // in a pass's bindings, before its draws
     * }</pre>
     *
     * @param unit zero-based texture unit
     */
    void bind(int unit);

    /**
     * What {@link #bind(int)} would bind, as a view of the installed device's texture: what a device set holds. Null
     * where it has no storage now, or no device texture behind it.
     */
    @Nullable
    default CgTextureView rhiView() {
        return null;
    }

    /**
     * How it is sampled where a draw names no sampler: its spec's filters and wraps. Null for GL's defaults, which
     * repeat; a texture standing for another (a frame graph's) answers with what stands behind it.
     */
    @Nullable
    default CgTextureSpec spec() {
        return null;
    }

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
}