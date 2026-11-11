package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgAccess;

import java.util.Arrays;

/**
 * Each storage's state, from which the graph states every transition (Unreal RDG's subresource states). For a buffer,
 * Vulkan's rule: a read after a write waits for the write and sees it, once per kind of reader; a write waits for the
 * write and the reads before it. For an image, the access each subresource was last moved to, which names its layout;
 * 0 is where it rests. Keyed by storage, not by graph resource, so two transients sharing pooled storage, a history's two
 * versions and storage used across frames and executions each come out right. GL-free.
 *
 * <pre>{@code
 * int from = states.access(CgResourceStates.buffer(id), CgAccess.COMPUTE_READ);   // what its read waits for, or 0
 * int[] subs = states.image(storage.key(), 1);                                   // each subresource's access
 * }</pre>
 *
 * <p>Buffer accesses may overlap: consecutive accesses through one overlap set, a compute pass's {@code skipBarrier}
 * bindings of one group, wait for nothing between them, as Unreal RDG's {@code SkipBarrier} UAVs and Niagara's
 * {@code BeginUAVOverlap} over its count buffer (the caller promises they touch disjoint words). The set's first access
 * waits as any would; any access after the set, through another set or none, waits for all of it.</p>
 */
final class CgResourceStates {

    static final int WRITES = CgAccess.COMPUTE_WRITE | CgAccess.COPY_WRITE | CgAccess.COLOR_WRITE | CgAccess.DEPTH_WRITE;

    /**
     * Open addressing over storage keys; per entry the last write's bits, reads since, reads made visible, and the
     * overlap set the last access was made through (0 for none).
     */
    private long[] keys = new long[64];
    private int[] lastWrite = new int[64], reads = new int[64], visible = new int[64], overlap = new int[64];
    /** Per entry: the compute pass that last claimed it, and which of its dispatches touches it first. */
    private int[] claimPass = new int[64], claimDispatch = new int[64];
    /** Per entry an image's subresources, level-major: each one's access, 0 at rest. Null for a buffer. */
    private int[][] subresources = new int[64][];
    private boolean[] used = new boolean[64];
    private int size;

    /** A buffer's key: its device name ({@code CgDeviceNames}). */
    static long buffer(int name) {
        return name & 0xFFFFFFFFL;
    }

    /** A texture's key, moved by range: its device name, apart from a buffer's. */
    static long texture(int name) {
        return (1L << 32) | (name & 0xFFFFFFFFL);
    }

    /** A graph storage's key, its textures moved as one: the storage's own id ({@link CgGraphStorage}). */
    static long storage(int id) {
        return (2L << 32) | (id & 0xFFFFFFFFL);
    }

    /**
     * Records an access of {@code bits} to buffer {@code key} and answers the bits of the barrier it needs first: the
     * accesses it must wait for, or 0.
     */
    int access(long key, int bits) {
        return access(key, bits, 0);
    }

    /**
     * {@link #access(long, int)} through overlap set {@code set}, or 0 for none: an access following one of the same
     * set needs no barrier.
     */
    int access(long key, int bits, int set) {
        int e = entry(key);
        int write = bits & WRITES, read = bits & ~WRITES;
        if (set != 0 && overlap[e] == set) {
            // Inside the set: its writes are seen by nothing until the next access outside it, which waits for them all.
            if (write != 0) {
                lastWrite[e] |= write;
                visible[e] = 0;
            }
            reads[e] |= read;
            return 0;
        }
        overlap[e] = set;
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
        return from;
    }

    /** Image {@code key}'s state: one access per subresource, at least {@code count} of them, 0 at rest. */
    int[] image(long key, int count) {
        int e = entry(key);
        int[] subs = subresources[e];
        if (subs == null || subs.length < count) subresources[e] = subs = subs == null ? new int[count] : Arrays.copyOf(subs, count);
        return subs;
    }

    /**
     * Whether moving a subresource from {@code from} (0 at rest) to {@code to} is a transition to state: a write on
     * either side, a layout that changes, or a subresource at rest, whose layout only the device knows.
     */
    static boolean moves(int from, int to) {
        if (from == 0 || ((from | to) & WRITES) != 0) return true;
        return CgAccess.layout(from) != CgAccess.layout(to);
    }

    /**
     * Whether dispatch {@code dispatch} of compute pass {@code pass} is the first of the pass to touch {@code key}: the
     * one whose accesses to it may be barriered at the pass's start. The first call for a key in a pass claims it.
     */
    boolean claim(long key, int pass, int dispatch) {
        int e = entry(key);
        if (claimPass[e] != pass) {
            claimPass[e] = pass;
            claimDispatch[e] = dispatch;
            return true;
        }
        return claimDispatch[e] == dispatch;
    }

    /** Forgets a storage that was freed: its name may be someone else's next, which rests where its maker left it. */
    void forget(long key) {
        int e = find(key);
        if (e < 0) return;
        lastWrite[e] = 0;
        reads[e] = 0;
        visible[e] = 0;
        overlap[e] = 0;
        if (subresources[e] != null) Arrays.fill(subresources[e], 0);
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
        lastWrite[i] = reads[i] = visible[i] = overlap[i] = claimPass[i] = claimDispatch[i] = 0;
        subresources[i] = null;
        size++;
        return i;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldWrite = lastWrite, oldReads = reads, oldVisible = visible, oldOverlap = overlap;
        int[] oldClaimPass = claimPass, oldClaimDispatch = claimDispatch;
        int[][] oldSubs = subresources;
        boolean[] oldUsed = used;
        int n = keys.length * 2;
        keys = new long[n];
        lastWrite = new int[n];
        reads = new int[n];
        visible = new int[n];
        overlap = new int[n];
        claimPass = new int[n];
        claimDispatch = new int[n];
        subresources = new int[n][];
        used = new boolean[n];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (!oldUsed[i]) continue;
            int e = entry(oldKeys[i]);
            lastWrite[e] = oldWrite[i];
            reads[e] = oldReads[i];
            visible[e] = oldVisible[i];
            overlap[e] = oldOverlap[i];
            claimPass[e] = oldClaimPass[i];
            claimDispatch[e] = oldClaimDispatch[i];
            subresources[e] = oldSubs[i];
        }
    }

    void clear() {
        Arrays.fill(used, false);
        Arrays.fill(subresources, null);
        size = 0;
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
