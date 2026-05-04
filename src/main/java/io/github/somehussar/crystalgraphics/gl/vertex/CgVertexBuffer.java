package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Owns the streaming base VBO for a given {@link CgVertexFormat}.
 *
 * <p>This is the VBO-only counterpart to {@link CgVertexArrayBinding} — it holds
 * the stream buffer without creating any VAO. {@link CgVertexArrayBinding} borrows
 * a {@code CgVertexBuffer} and configures a non-instanced VAO on top of it.
 * {@link CgInstanceVertexArrayBinding} borrows a {@code CgVertexBuffer} directly for the
 * instanced VAO path, avoiding a wasted non-instanced VAO creation.</p>
 *
 * <p>Create via {@link CgVertexBufferRegistry}; do not construct directly.
 * Do NOT call {@link #delete()} on registry-owned instances — the registry owns
 * the lifecycle and calls {@code delete()} during context teardown.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CgVertexBuffer {

    /**
     * Initial VBO capacity expressed in quads (4 vertices each).
     * At 4096 quads the buffer holds 16 384 vertices before growing via orphan or subdata.
     */
    static final int INITIAL_QUADS = 4096;

    /** Vertex format that describes the attribute layout of {@link #streamBuffer}. */
    @Getter
    private final CgVertexFormat format;

    /**
     * The underlying streaming VBO.
     * Borrowed by VAOs — do not delete this directly; the registry owns the lifetime.
     */
    @Getter
    private final CgStreamBuffer streamBuffer;

    public static CgVertexBuffer create(CgVertexFormat format) {
        int capacityBytes = format.getStride() * 4 * INITIAL_QUADS;
        return new CgVertexBuffer(format, CgStreamBuffer.create(capacityBytes));
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
