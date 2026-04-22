package io.github.somehussar.crystalgraphics.gl.pass;

/**
 * Declares the projection domain for a render pass.
 *
 * <p>This enum does not compute or store projection matrices — it is a
 * semantic tag that tells the pass what kind of projection setup is
 * expected. The actual matrix computation is the responsibility of the
 * concrete pass subclass (e.g. {@link CgUiPass} builds an orthographic
 * matrix, {@link CgWorldOverlayPass} receives a world MVP).</p>
 *
 * <p>The projection mode helps batching logic and future tooling
 * understand whether vertices should be CPU-pretransformed (2D ortho)
 * or GPU-transformed (world 3D).</p>
 */
public enum CgProjectionMode {

    /**
     * 2D orthographic projection.
     *
     * <p>Used by UI passes. Vertices are expected to be CPU-pretransformed
     * into screen-space coordinates. The pass sets a simple ortho matrix
     * as the projection uniform.</p>
     */
    ORTHOGRAPHIC_2D,

    /**
     * 3D world-space projection.
     *
     * <p>Used by world overlay passes. Vertices are in world coordinates
     * and transformed on the GPU via model-view-projection matrices
     * provided by the caller or derived from the game's camera.</p>
     */
    WORLD_3D,

    /**
     * Fullscreen / NDC projection.
     *
     * <p>Used by fullscreen post-processing passes. A single fullscreen
     * triangle or quad is drawn in normalized device coordinates
     * ({@code -1..+1}), requiring no projection matrix.</p>
     */
    FULLSCREEN_NDC
}
