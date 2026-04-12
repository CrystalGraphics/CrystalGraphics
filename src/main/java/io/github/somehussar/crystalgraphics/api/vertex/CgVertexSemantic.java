package io.github.somehussar.crystalgraphics.api.vertex;

/**
 * Semantic role of a vertex attribute within a {@link CgVertexFormat}.
 *
 * <p>Used by {@link CgVertexAttribute} to describe the purpose of each
 * attribute slot, enabling format-aware vertex writers to route fluent
 * calls (position/uv/color/normal) to the correct staging offsets
 * without relying on attribute name conventions.</p>
 *
 * <p>Multiple attributes with the same semantic are distinguished by
 * {@link CgVertexAttribute#getSemanticIndex()} (e.g. UV0, UV1).</p>
 */
public enum CgVertexSemantic {
    /** Vertex position (2D or 3D). */
    POSITION,
    /** Texture coordinates. */
    UV,
    /** Vertex color. */
    COLOR,
    /** Surface normal. */
    NORMAL,
    /** No specific semantic — custom or application-defined. */
    GENERIC
}
