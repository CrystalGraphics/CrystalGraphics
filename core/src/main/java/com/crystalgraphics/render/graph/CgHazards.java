package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.device.command.CgAccess;

import java.util.Arrays;

/**
 * Which barrier each access needs, from the accesses before it to the same storage: Vulkan's rule — a read after a
 * write waits for the write and sees it, once per kind of reader; a write waits for the write and the reads before
 * it. Keyed by storage, not by graph resource, so two transients sharing pooled storage, a history's two versions and
 * a buffer used across frames each come out right. GL-free.
 *
 * <pre>{@code
 * int from = hazards.access(key, CgAccess.COMPUTE_READ);
 * if (from != 0) CgGL.cgBufferBarrier(buffer, from, CgAccess.COMPUTE_READ);
 * }</pre>
 *
 * <p>Only a hazard a kernel takes part in answers a barrier. Between draws, copies and uploads the backend orders
 * everything itself, as it did before the graph had kernels; their accesses are still recorded, so the kernel after
 * them waits for them.</p>
 */
final class CgHazards {

    static final int WRITES = CgAccess.COMPUTE_WRITE | CgAccess.COPY_WRITE | CgAccess.COLOR_WRITE;
    private static final int COMPUTE = CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE;

    /** Open addressing over storage keys; per entry the last write's bits, reads since, and reads made visible. */
    private long[] keys = new long[64];
    private int[] lastWrite = new int[64], reads = new int[64], visible = new int[64];
    private boolean[] used = new boolean[64];
    private int size;

    /** A buffer's key: its GL name. */
    static long buffer(int glBuffer) {
        return glBuffer & 0xFFFFFFFFL;
    }

    /** A texture's key: its GL name, apart from a buffer's. */
    static long texture(int glTexture) {
        return (1L << 32) | (glTexture & 0xFFFFFFFFL);
    }

    /**
     * Records an access of {@code bits} to {@code key} and answers the bits of the barrier it needs first: the
     * accesses it must wait for, or 0.
     */
    int access(long key, int bits) {
        int e = entry(key);
        int write = bits & WRITES, read = bits & ~WRITES;
        int from;
        if (write != 0) {
            // Readers that each waited for the last write carry it: waiting for them is enough.
            boolean chained = reads[e] != 0 && (visible[e] & reads[e]) == reads[e];
            from = chained ? reads[e] : lastWrite[e] | reads[e];
            lastWrite[e] = write;
            reads[e] = 0;
            visible[e] = 0;
        } else {
            from = lastWrite[e] != 0 && (visible[e] & read) != read ? lastWrite[e] : 0;
            if (from != 0) visible[e] |= read;
            reads[e] |= read;
        }
        return from != 0 && ((from | bits) & COMPUTE) != 0 ? from : 0;
    }

    /** Forgets a storage that was freed: its name may be someone else's next. */
    void forget(long key) {
        int e = find(key);
        if (e < 0) return;
        lastWrite[e] = 0;
        reads[e] = 0;
        visible[e] = 0;
    }

    private int find(long key) {
        int mask = keys.length - 1;
        for (int i = hash(key) & mask; used[i]; i = (i + 1) & mask) if (keys[i] == key) return i;
        return -1;
    }

    private int entry(long key) {
        int mask = keys.length - 1;
        int i = hash(key) & mask;
        for (; used[i]; i = (i + 1) & mask) if (keys[i] == key) return i;
        if ((size + 1) * 2 > keys.length) {
            grow();
            return entry(key);
        }
        used[i] = true;
        keys[i] = key;
        lastWrite[i] = reads[i] = visible[i] = 0;
        size++;
        return i;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldWrite = lastWrite, oldReads = reads, oldVisible = visible;
        boolean[] oldUsed = used;
        int n = keys.length * 2;
        keys = new long[n];
        lastWrite = new int[n];
        reads = new int[n];
        visible = new int[n];
        used = new boolean[n];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (!oldUsed[i]) continue;
            int e = entry(oldKeys[i]);
            lastWrite[e] = oldWrite[i];
            reads[e] = oldReads[i];
            visible[e] = oldVisible[i];
        }
    }

    void clear() {
        Arrays.fill(used, false);
        size = 0;
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
