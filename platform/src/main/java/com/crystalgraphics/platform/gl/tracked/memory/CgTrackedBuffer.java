package com.crystalgraphics.platform.gl.tracked.memory;

import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A GL buffer object's storage on the tracked backend, with GL's ordering: a draw sees the contents as they
 * were when it was issued, although it runs later. A CPU write into memory a frame in flight still reads goes to
 * fresh memory instead — ANGLE's and Zink's buffer rename — and a device-local buffer is written by a staged
 * copy at the point of the call.
 *
 * <pre>{@code
 * CgTrackedBuffer vbo = new CgTrackedBuffer(tracker, "quad");
 * vbo.data(96, vertices, true);          // glBufferData: new storage
 * ... a draw reads it ...
 * vbo.subData(0, moreVertices);          // that draw keeps the old bytes; this lands in a renamed copy
 * }</pre>
 */
public final class CgTrackedBuffer {

    private final CgTracker tracker;
    private final String label;
    private CgAllocation current;
    private boolean persistent;
    private ByteBuffer staging;
    private long stagingOffset;

    public CgTrackedBuffer(CgTracker tracker, String label) {
        this.tracker = tracker;
        this.label = label;
    }

    /** The storage a draw binds now; {@code null} before any {@link #data}. */
    public CgAllocation allocation() { return current; }

    public long size() { return current == null ? 0 : current.size; }

    /** {@code glBufferData}: new storage, the old freed once no frame reads it. */
    public void data(long size, ByteBuffer initial, boolean hostVisible) {
        if (persistent) throw new IllegalStateException(label + " has immutable storage");
        replace(tracker.allocate(size, hostVisible, label));
        if (initial != null) subData(0, initial);
    }

    /** {@code glBufferStorage}: immutable, host-visible storage. A persistent one is mapped for good and never renamed. */
    public void storage(long size, ByteBuffer initial, boolean persistentMapping) {
        if (persistent) throw new IllegalStateException(label + " has immutable storage");
        replace(tracker.allocate(size, true, label));
        if (initial != null) subData(0, initial);
        persistent = persistentMapping;
    }

    /** {@code glBufferSubData}. */
    public void subData(long offset, ByteBuffer data) {
        CgAllocation a = require();
        if (!a.hostVisible()) {
            ByteBuffer copy = data.duplicate();
            tracker.transfer().writeBuffer(a.buffer, a.offset + offset, copy);
            tracker.markUsed(a);
            return;
        }
        if (!persistent && !tracker.writable(a)) a = rename(true);
        ByteBuffer to = a.memory();
        to.position((int) offset);
        to.put(data.duplicate());
    }

    /**
     * {@code glMapBufferRange}: the bytes to write, or to read once the GPU has written them.
     *
     * @param invalidateBuffer the old contents may go: new storage
     * @param unsynchronized   the caller keeps the GPU off the range itself (core's frame ring): the memory as is
     */
    public ByteBuffer map(long offset, long length, boolean read, boolean invalidateBuffer, boolean unsynchronized) {
        CgAllocation a = require();
        if (!a.hostVisible()) {
            if (read) throw new UnsupportedOperationException(label + ": reading back a device-local buffer");
            staging = ByteBuffer.allocateDirect((int) length).order(ByteOrder.nativeOrder());
            stagingOffset = offset;
            return staging;
        }
        if (read) {
            if (a.lastUse > tracker.device().retiredFrame()) tracker.device().waitRetired(a.lastUse);
        } else if (invalidateBuffer && !persistent) {
            a = rename(false);
        } else if (!persistent && !unsynchronized && !tracker.writable(a)) {
            a = rename(true);
        }
        ByteBuffer m = a.memory();
        m.limit((int) (offset + length));
        m.position((int) offset);
        return m.slice().order(ByteOrder.nativeOrder());
    }

    /** {@code glUnmapBuffer}: a device-local buffer's staged bytes are copied in here. */
    public void unmap() {
        if (staging != null) {
            staging.clear();
            tracker.transfer().writeBuffer(current.buffer, current.offset + stagingOffset, staging);
            tracker.markUsed(current);
            staging = null;
        }
    }

    /** {@code glDeleteBuffers}. */
    public void release() {
        if (current != null) tracker.free(current);
        current = null;
    }

    private CgAllocation require() {
        if (current == null) throw new IllegalStateException(label + " has no storage");
        return current;
    }

    private void replace(CgAllocation next) {
        if (current != null) tracker.free(current);
        current = next;
    }

    private CgAllocation rename(boolean preserve) {
        CgAllocation old = current;
        CgAllocation fresh = tracker.allocate(old.size, true, label);
        if (preserve) fresh.memory().put(old.memory());
        tracker.stats.renames++;
        replace(fresh);
        return fresh;
    }
}
