package io.github.somehussar.crystalgraphics.gl.buffer;

import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/**
 * Preferred streaming path: 3-slot ring buffer with GL fence sync.
 *
 * <p>Each slot is written round-robin. Before reusing a slot, we wait
 * on its fence to ensure the GPU has finished reading that region.
 * Uploads that exceed a single slot fall back to full-buffer orphan.</p>
 */
public class MapAndSyncStreamBuffer extends CgStreamBuffer {

    /** Triple-buffer: lets CPU write slot N while GPU reads N-1 and N-2. */
    private static final int RING_FRAMES = 3;

    /** UBO/SSBO alignment floor; safe default for glMapBufferRange offsets. */
    private static final int SLOT_ALIGNMENT = 256;

    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L; // 5 seconds

    private final GLSync[] fences = new GLSync[RING_FRAMES];
    private int currentSlot;
    private int slotSize;
    /** Tracks whether the last map() fell through to orphan (oversize path). */
    private boolean lastMapUsedOrphan;

    public MapAndSyncStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        this.slotSize = alignUp(Math.max(1, capacityBytes / RING_FRAMES), SLOT_ALIGNMENT);
        this.capacityBytes = this.slotSize * RING_FRAMES;
        bind();
        GL15.glBufferData(target, this.capacityBytes, GL15.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        // Oversize data can't fit in one slot — fall back to orphan for this upload.
        if (sizeBytes > slotSize) {
            return mapOrphan(sizeBytes);
        }

        lastMapUsedOrphan = false;

        // Block until the GPU finishes reading this slot from a previous frame.
        if (fences[currentSlot] != null) {
            waitOnFence(fences[currentSlot]);
            ARBSync.glDeleteSync(fences[currentSlot]);
            fences[currentSlot] = null;
        }

        int offset = currentSlot * slotSize;
        bind();
        // UNSYNCHRONIZED: we already waited on the fence, so we promise the driver
        // this region is safe to write. FLUSH_EXPLICIT: we'll flush in commit().
        ByteBuffer mapped = GL30.glMapBufferRange(target, offset, sizeBytes,
                GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_FLUSH_EXPLICIT_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT,
                null);
        
        if (mapped == null) throw new IllegalStateException("glMapBufferRange returned null (offset=" + offset + ", size=" + sizeBytes + ")");
        return mapped;
    }

    @Override
    public int commit(int usedBytes) {
        // FlushMappedBufferRange offset is relative to the start of the *mapped* range,
        // so it is always 0 here (we map exactly at slot or orphan start). dataOffset
        // is the absolute buffer offset returned to the caller for VAO pointer setup.
        int dataOffset = lastMapUsedOrphan ? 0 : currentSlot * slotSize;
        GL30.glFlushMappedBufferRange(target, 0, usedBytes);
        GL15.glUnmapBuffer(target);
        if (!lastMapUsedOrphan) {
            // Fence marks the point where the GPU will finish consuming this slot.
            fences[currentSlot] = ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            currentSlot = (currentSlot + 1) % RING_FRAMES;
        }
        lastMapUsedOrphan = false;
        return dataOffset;
    }

    @Override
    public void delete() {
        deleteAllFences();
        GL15.glDeleteBuffers(glBuffer);
    }

    private void deleteAllFences() {
        for (int i = 0; i < fences.length; i++) {
            if (fences[i] != null) {
                ARBSync.glDeleteSync(fences[i]);
                fences[i] = null;
            }
        }
    }

    private void waitOnFence(GLSync fence) {
        long elapsed = 0;
        while (elapsed < FENCE_TIMEOUT_NS) {
            long waitNs = Math.min(1_000_000L, FENCE_TIMEOUT_NS - elapsed);
            int result = ARBSync.glClientWaitSync(fence, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT, waitNs);
            if (result == ARBSync.GL_ALREADY_SIGNALED || result == ARBSync.GL_CONDITION_SATISFIED) {
                return;
            }
            if (result == ARBSync.GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed for stream buffer fence");
            
            elapsed += waitNs;
        }
        throw new IllegalStateException("Stream buffer fence not signaled within " + (FENCE_TIMEOUT_NS / 1_000_000_000L) + "s — possible GPU hang");
    }

    private ByteBuffer mapOrphan(int sizeBytes) {
        lastMapUsedOrphan = true;
        // Orphaning discards the old backing store, so all outstanding fences are stale.
        deleteAllFences();
        currentSlot = 0;
        bind();
        if (sizeBytes > capacityBytes) {
            capacityBytes = alignUp(sizeBytes, SLOT_ALIGNMENT);
            slotSize = alignUp(Math.max(1, capacityBytes / RING_FRAMES), SLOT_ALIGNMENT);
        }
        GL15.glBufferData(target, capacityBytes, GL15.GL_STREAM_DRAW);
        ByteBuffer mapped = GL30.glMapBufferRange(target, 0, sizeBytes,
                GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT,
                null);
        
        if (mapped == null) throw new IllegalStateException("glMapBufferRange (orphan) returned null (size=" + sizeBytes + ")");
        return mapped;
    }

    private static int alignUp(int value, int alignment) {
        int mask = alignment - 1;
        return (value + mask) & ~mask;
    }
}
