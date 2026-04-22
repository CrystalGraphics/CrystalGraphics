package io.github.somehussar.crystalgraphics.gl.pass;

import org.lwjgl.opengl.GL11;

/**
 * Defines what a render pass should clear when it begins.
 *
 * <p>Clear policy is applied at pass begin, before any drawing occurs.
 * The policy determines which framebuffer attachments (if any) are cleared
 * via {@code glClear}.</p>
 *
 * <p>For UI passes, {@link #NONE} is typical (the UI is composited on top
 * of whatever was already rendered). For off-screen FBO passes,
 * {@link #COLOR} or {@link #COLOR_DEPTH} is common.</p>
 */
public enum CgClearPolicy {

    /** Do not clear anything. Typical for overlay/UI passes. */
    NONE(0),

    /** Clear only the color buffer. */
    COLOR(GL11.GL_COLOR_BUFFER_BIT),

    /** Clear only the depth buffer. */
    DEPTH(GL11.GL_DEPTH_BUFFER_BIT),

    /** Clear both color and depth buffers. */
    COLOR_DEPTH(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT),

    /** Clear color, depth, and stencil buffers. */
    ALL(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

    private final int glMask;

    CgClearPolicy(int glMask) {
        this.glMask = glMask;
    }

    /**
     * Returns the GL bitmask to pass to {@code glClear()}.
     *
     * @return the combined {@code GL_*_BUFFER_BIT} mask, or 0 for {@link #NONE}
     */
    public int getGlMask() {
        return glMask;
    }

    /**
     * Returns whether this policy clears anything at all.
     *
     * @return {@code true} if at least one buffer bit is set
     */
    public boolean clears() {
        return glMask != 0;
    }
}
