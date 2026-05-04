package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Owns the streaming instance VBO for a given {@link CgInstanceFormat}.
 *
 * <p>Shared across all base sources that use the same instance layout — one
 * instance VBO per layout, not per (format, layout) pair. This allows different
 * base meshes to share the same per-instance data stream when they use the same
 * instance layout.</p>
 *
 * <p>Create via {@link CgVertexBufferRegistry#getOrCreateInstanced}; do not construct directly.
 * Do NOT call {@link #delete()} on registry-owned instances — the registry owns
 * the lifecycle and calls {@code delete()} during context teardown.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CgInstanceVertexBuffer {

    /**
     * Initial VBO capacity in instances.
     * 256 instances × layout stride is a conservative starting point; the stream
     * buffer grows automatically via orphan or subdata when the staging exceeds capacity.
     */
    private static final int INITIAL_INSTANCES = 256;

    /** Instance format that describes the attribute layout of {@link #streamBuffer}. */
    @Getter
    private final CgInstanceFormat layout;

    /**
     * The underlying streaming VBO (instance data, divisor=1).
     * Borrowed by instanced VAOs — do not delete this directly; the registry owns the lifetime.
     */
    @Getter
    private final CgStreamBuffer streamBuffer;

    public static CgInstanceVertexBuffer create(CgInstanceFormat layout) {
        int capacityBytes = layout.getStride() * INITIAL_INSTANCES;
        return new CgInstanceVertexBuffer(layout, CgStreamBuffer.create(capacityBytes));
    }

    /**
     * Deletes the owned stream buffer.
     *
     * <p><strong>Must be called on the GL thread.</strong> Call only during context teardown,
     * after all VAOs that reference this stream's VBO have been deleted.</p>
     */
    public void delete() {
        streamBuffer.delete();
    }
}
