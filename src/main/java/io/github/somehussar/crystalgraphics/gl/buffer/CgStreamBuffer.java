package io.github.somehussar.crystalgraphics.gl.buffer;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;

import lombok.Getter;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * Abstract streaming VBO that handles per-frame dynamic vertex data upload.
 *
 * <p>One CgStreamBuffer exists per CgVertexFormat in active use. All consumers
 * sharing a format share the same stream buffer.</p>
 */
public abstract class CgStreamBuffer {

    @Getter
    protected final int glBuffer;
    @Getter
    protected final int target;
    @Getter
    protected int capacityBytes;
    @Getter
    protected int writeOffset;

    protected CgStreamBuffer(int target, int capacityBytes) {
        this.glBuffer = GL15.glGenBuffers();
        this.target = target;
        this.capacityBytes = capacityBytes;
        this.writeOffset = 0;
    }

    /**
     * Reserves space and returns a ByteBuffer to write into.
     * Valid until {@link #commit(int)} is called.
     */
    public abstract ByteBuffer map(int sizeBytes);

    /**
     * Commits written data.
     *
     * @param usedBytes actual bytes written
     * @return byte offset in the GL buffer where this data starts
     */
    public abstract int commit(int usedBytes);

    public void bind() {
        GL15.glBindBuffer(target, glBuffer);
    }

    public void unbind() {
        GL15.glBindBuffer(target, 0);
    }

    public abstract void delete();

    /**
     * Waterfall: sync ring-buffer (best) → orphan map (no sync) → CPU staging (GL1.5 baseline).
     */
    public static CgStreamBuffer create(int capacityBytes) {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps.isArbSync()) return new MapAndSyncStreamBuffer(GL15.GL_ARRAY_BUFFER, capacityBytes);
        if (caps.isMapBufferRangeSupported()) return new MapAndOrphanStreamBuffer(GL15.GL_ARRAY_BUFFER, capacityBytes);

        return new SubDataStreamBuffer(GL15.GL_ARRAY_BUFFER, capacityBytes);
    }
}
