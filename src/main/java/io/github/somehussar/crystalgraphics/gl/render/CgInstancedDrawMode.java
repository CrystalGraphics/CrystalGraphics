package io.github.somehussar.crystalgraphics.gl.render;

/**
 * Topology mode for {@link CgInstancedBatchRenderer#flush(CgInstancedDrawMode)}.
 *
 * <ul>
 *   <li>{@link #INDEXED_QUADS} — base geometry is quads (4 vertices each) drawn via the shared
 *       quad index buffer. Base vertex count must be a multiple of 4.</li>
 *   <li>{@link #ARRAY_TRIANGLES} — base geometry is explicit triangles. Base vertex count
 *       must be a multiple of 3.</li>
 * </ul>
 */
public enum CgInstancedDrawMode {
    /** Quad geometry drawn via shared quad IBO. Base vertex count must be a multiple of 4. */
    INDEXED_QUADS,

    /** Triangle list drawn via glDrawArraysInstanced. Base vertex count must be a multiple of 3. */
    ARRAY_TRIANGLES
}
