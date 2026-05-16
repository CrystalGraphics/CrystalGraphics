package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgGL;

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

    private final Long[] fences = new Long[RING_FRAMES];
    private Long orphanFence;
    private int currentSlot;
    private int slotSize;
    /** Tracks whether the last map() fell through to orphan (oversize path). */
    private boolean lastMapUsedOrphan;

    public MapAndSyncStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        this.slotSize = alignUp(Math.max(1, capacityBytes / RING_FRAMES), SLOT_ALIGNMENT);
        this.capacityBytes = this.slotSize * RING_FRAMES;
        bind();
        CgGL.glBufferData(target, this.capacityBytes, CgGL.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        // Oversize data can't fit in one slot — fall back to orphan for this upload.
        if (sizeBytes > slotSize) {
            return mapOrphan(sizeBytes);
        }

        lastMapUsedOrphan = false;

        // If the previous upload used the orphan path, a later non-orphan slot write
        // must not reuse the new backing store until the draw that consumed it completes.
        if (orphanFence != null) {
            waitOnFence(orphanFence);
            CgGL.glDeleteSync(orphanFence);
            orphanFence = null;
        }

        // Block until the GPU finishes reading this slot from a previous frame.
        if (fences[currentSlot] != null) {
            waitOnFence(fences[currentSlot]);
            CgGL.glDeleteSync(fences[currentSlot]);
            fences[currentSlot] = null;
        }

        int offset = currentSlot * slotSize;
        bind();
        // UNSYNCHRONIZED: we already waited on the fence, so we promise the driver
        // this region is safe to write. FLUSH_EXPLICIT: we'll flush in commit().
        ByteBuffer mapped = CgGL.glMapBufferRange(target, offset, sizeBytes,
                CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_FLUSH_EXPLICIT_BIT | CgGL.GL_MAP_UNSYNCHRONIZED_BIT,
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
        if (!lastMapUsedOrphan) {
            CgGL.glFlushMappedBufferRange(target, 0, usedBytes);
        }
        CgGL.glUnmapBuffer(target);
        return dataOffset;
    }

    @Override
    public void afterSubmit() {
        if (lastMapUsedOrphan) {
            orphanFence = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        } else {
            // Fence marks the point after the draw using this slot has been queued.
            fences[currentSlot] = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            currentSlot = (currentSlot + 1) % RING_FRAMES;
        }
        lastMapUsedOrphan = false;
    }

    private void waitOnFence(long fence) {
        long elapsed = 0;
        while (elapsed < FENCE_TIMEOUT_NS) {
            long waitNs = Math.min(1_000_000L, FENCE_TIMEOUT_NS - elapsed);
            int result = CgGL.glClientWaitSync(fence, CgGL.GL_SYNC_FLUSH_COMMANDS_BIT, waitNs);
            if (result == CgGL.GL_ALREADY_SIGNALED || result == CgGL.GL_CONDITION_SATISFIED) {
                return;
            }
            if (result == CgGL.GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed for stream buffer fence");
            
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
        CgGL.glBufferData(target, capacityBytes, CgGL.GL_STREAM_DRAW);
        ByteBuffer mapped = CgGL.glMapBufferRange(target, 0, sizeBytes,
                CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_INVALIDATE_BUFFER_BIT,
                null);
        
        if (mapped == null) throw new IllegalStateException("glMapBufferRange (orphan) returned null (size=" + sizeBytes + ")");
        return mapped;
    }

    private static int alignUp(int value, int alignment) {
        int mask = alignment - 1;
        return (value + mask) & ~mask;
    }

    private void deleteAllFences() {
        for (int i = 0; i < fences.length; i++) {
            if (fences[i] != null) {
                CgGL.glDeleteSync(fences[i]);
                fences[i] = null;
            }
        }
        if (orphanFence != null) {
            CgGL.glDeleteSync(orphanFence);
            orphanFence = null;
        }
    }

    @Override
    public void deleteGlResources() {
        deleteAllFences();
        CgGL.glDeleteBuffers(glBuffer);
    }
}
