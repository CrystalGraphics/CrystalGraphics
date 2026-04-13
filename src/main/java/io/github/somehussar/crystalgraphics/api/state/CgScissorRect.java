package io.github.somehussar.crystalgraphics.api.state;

import lombok.Getter;
import org.lwjgl.opengl.GL11;

/**
 * Reusable mutable clip rectangle for scissor operations.
 *
 * <p>Designed for pooled usage in scissor stacks and draw-list command recording.
 * Instances should be preallocated and reused — never heap-allocated per frame.</p>
 *
 * <p>Public consumers may read fields via the getters, mutate via {@link #set(int, int, int, int)},
 * and apply the scissor to GL via {@link #applyGl()}.</p>
 *
 * <h3>Merge rule</h3>
 * <p>Draw-list merge detection compares scissor rects by <strong>field equality</strong>,
 * not reference equality, because pooled entries are reused across mutations.</p>
 */
@Getter
public final class CgScissorRect {
    
    private int x;
    private int y;
    private int width;
    private int height;

    public CgScissorRect() {}

    /**
     * Sets all fields of this rectangle. Returns {@code this} for chaining.
     *
     * @param x      x-origin (screen-space pixels)
     * @param y      y-origin (screen-space pixels)
     * @param width  width (pixels, must be ≥ 0)
     * @param height height (pixels, must be ≥ 0)
     * @return this rectangle for chaining
     */
    public CgScissorRect set(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * Returns whether this scissor rect represents an enabled (non-zero area) clip.
     */
    public boolean isEnabled() {
        return width > 0 && height > 0;
    }

    /**
     * Applies this scissor rect to the current GL context via {@code glScissor}.
     * Does NOT enable/disable {@code GL_SCISSOR_TEST} — the caller manages that.
     */
    public void applyGl() {
        GL11.glScissor(x, y, width, height);
    }

    /**
     * Field-equality comparison for draw-list merge detection.
     * Reference equality is incorrect because pooled rects are reused.
     */
    public boolean matches(int ox, int oy, int ow, int oh) {
        return x == ox && y == oy && width == ow && height == oh;
    }

    /**
     * Field-equality comparison against another {@link CgScissorRect}.
     */
    public boolean matches(CgScissorRect other) {
        return other != null && x == other.x && y == other.y
                && width == other.width && height == other.height;
    }

    @Override
    public String toString() {
        return "CgScissorRect{x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + "}";
    }
}
