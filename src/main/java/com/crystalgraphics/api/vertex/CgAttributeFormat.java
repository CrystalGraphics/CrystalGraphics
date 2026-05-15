package com.crystalgraphics.api.vertex;

/**
 * Common interface for vertex attribute layout descriptors.
 *
 * <p>Implemented by {@link CgVertexFormat} (per-vertex attributes) and
 * {@link CgInstanceFormat} (per-instance attributes). Allows VAO setup code
 * to work uniformly against either layout type without caring which side
 * (base vs instance) it is configuring.</p>
 */
public interface CgAttributeFormat {
    /** Total byte stride per element (vertex or instance). */
    int getStride();
    /** Number of physical attributes in this layout. */
    int getAttributeCount();
    /** Returns the physical attribute at the given index. */
    CgVertexAttribute getAttribute(int index);
    /**
     * Convenience: {@code stride / Float.BYTES}.
     * Number of float slots per element for staging purposes.
     */
    int getFloatsPerElement();
}
