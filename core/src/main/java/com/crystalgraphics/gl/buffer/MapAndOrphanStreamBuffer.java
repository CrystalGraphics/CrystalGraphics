package com.crystalgraphics.gl.buffer;


import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;
import java.nio.ByteBuffer;

/**
 * Shader-buffer storage: every upload orphans, via {@code glMapBufferRange} with
 * {@code GL_MAP_INVALIDATE_BUFFER_BIT}, and lands at offset 0.
 *
 * <p>On each {@link #map} call, the entire buffer is orphaned (driver allocates
 * new backing store). This avoids CPU-GPU sync stalls at the cost of one
 * driver-side allocation per map.</p>
 */
public class MapAndOrphanStreamBuffer extends CgStreamBuffer {

    public MapAndOrphanStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        // Pre-allocate the GL buffer
        bind();
        CgGL.glBufferData(target, capacityBytes, CgGL.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        bind();
        if (sizeBytes > capacityBytes) {
            CgGL.glBufferData(target, sizeBytes, CgGL.GL_STREAM_DRAW);
            capacityBytes = sizeBytes;
        }
        
        ByteBuffer mapped = CgGL.glMapBufferRange(target, 0, sizeBytes,
                CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_INVALIDATE_BUFFER_BIT, lastMapping);

        if (mapped == null) throw new IllegalStateException("glMapBufferRange (orphan) returned null (size=" + sizeBytes + ")");
        // LWJGL returns the old wrapper for the same address and size, position and all.
        mapped.clear();
        lastMapping = mapped;
        return mapped;
    }

    /** The last mapping, offered back to the driver so its wrapper can be reused. */
    private ByteBuffer lastMapping;

    @Override
    public int commit(int usedBytes) {
        CgGL.glUnmapBuffer(target);
        return 0; // orphaning resets to offset 0
    }

    /**
     * Small uploads go straight to {@code glBufferSubData} rather than orphan-mapping — see
     * {@link CgStreamBuffer#SMALL_UPLOAD_THRESHOLD_BYTES} for the measurements motivating this.
     *
     * <p>Safe because this tier always writes at offset 0 and never reads back what it wrote:
     * a subdata write replaces exactly the bytes the next draw will consume. It does forgo the
     * orphan hint, so in principle the driver could stall if the previous contents were still
     * in flight — but for a write this small that is strictly cheaper in practice than paying
     * the map/unmap overhead every single call, which was costing ~0.19 ms each.</p>
     */
    @Override
    protected boolean uploadSmall(float[] data, int floatCount, int byteCount) {
        if (byteCount > capacityBytes) return false; // let the normal path handle the grow
        // Where a map costs nothing, the orphan is the cheap path and a partial write is the dear one.
        if (CgCapabilities.detect().isMappingFree()) return false;

        if (scratch == null || scratch.capacity() < byteCount) {
            scratch = CgBufferUtils.createByteBuffer(Math.max(byteCount, SMALL_UPLOAD_THRESHOLD_BYTES));
        }
        scratch.clear();
        scratch.asFloatBuffer().put(data, 0, floatCount);
        scratch.position(0).limit(byteCount);

        bind();
        CgGL.glBufferSubData(target, 0L, scratch);
        return true;
    }

    /** Reused staging buffer for {@link #uploadSmall} — grow-only, allocated on first small upload. */
    private ByteBuffer scratch;

    @Override
    public void deleteGlResources() {
        CgGL.glDeleteBuffers(glBuffer);
    }
}
