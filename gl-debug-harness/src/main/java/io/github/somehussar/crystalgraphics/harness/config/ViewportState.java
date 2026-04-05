package io.github.somehussar.crystalgraphics.harness.config;

/**
 * Typed accessor for the current viewport (screen) dimensions.
 *
 * <p>This object is mutable because the LWJGL Display can be resized at runtime.
 * The {@link io.github.somehussar.crystalgraphics.harness.InteractiveSceneRunner}
 * updates these values when {@code Display.wasResized()} is detected.
 * All consumers (scenes, overlays, renderers) read viewport dimensions
 * through this single object rather than duplicating mutable ints.</p>
 *
 * <p><b>Thread safety</b>: viewport state is only mutated and read on the
 * LWJGL render thread, so no synchronization is needed.</p>
 *
 * @see HarnessContext#getViewport()
 */
public final class ViewportState {

    private int width;
    private int height;

    /**
     * Creates a new viewport state with the given initial dimensions.
     *
     * @param width  initial viewport width in pixels (must be positive)
     * @param height initial viewport height in pixels (must be positive)
     * @throws IllegalArgumentException if width or height is not positive
     */
    public ViewportState(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Viewport dimensions must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
    }

    /**
     * Returns the current viewport width in pixels.
     *
     * @return viewport width, always positive
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns the current viewport height in pixels.
     *
     * @return viewport height, always positive
     */
    public int getHeight() {
        return height;
    }

    /**
     * Computes the aspect ratio (width / height) as a float.
     *
     * <p>This is a convenience method used frequently for perspective
     * projection matrix construction.</p>
     *
     * @return the aspect ratio, always positive
     */
    public float getAspectRatio() {
        return (float) width / (float) height;
    }

    /**
     * Updates the viewport dimensions. Called by the runtime when the
     * display is resized.
     *
     * @param width  new viewport width in pixels (must be positive)
     * @param height new viewport height in pixels (must be positive)
     * @throws IllegalArgumentException if width or height is not positive
     */
    public void update(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Viewport dimensions must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
    }

    @Override
    public String toString() {
        return "ViewportState[" + width + "x" + height + "]";
    }
}
