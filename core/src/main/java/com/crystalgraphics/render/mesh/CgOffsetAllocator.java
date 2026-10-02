package com.crystalgraphics.render.mesh;

import java.util.Arrays;

/**
 * Ranges of one fixed-size space, allocated and freed in O(1): a two-level bitfield over 256 size classes spaced as a
 * small float (3 mantissa bits), and neighbour links that merge a freed range with free ranges either side. The unit
 * is the caller's: a vertex, an index, a byte.
 *
 * <pre>{@code
 * CgOffsetAllocator vertices = new CgOffsetAllocator(65536);
 * int node = vertices.allocate(24);             // -1 when no free range fits
 * int first = vertices.offset(node);            // where the 24 start
 * vertices.free(node);
 * }</pre>
 *
 * <ul>
 *   <li>Answers a node, not an offset: keep it, since {@link #free} takes the node.</li>
 *   <li>A size of 0 is not an allocation; a caller with nothing to place allocates nothing.</li>
 *   <li>A request rounds up to its size class, so a space serves a request as large as itself only when its size is
 *       a class's own size: make a space for one known request {@link #fittingSize} long.</li>
 *   <li>Not thread-safe.</li>
 * </ul>
 *
 * <p>Ported from Sebastian Aaltonen's OffsetAllocator ({@code offsetAllocator.cpp}, MIT), the allocator Bevy's mesh
 * allocator uses. Node storage grows here, where the original takes a fixed maximum.</p>
 */
public final class CgOffsetAllocator {

    public static final int NO_SPACE = -1;

    private static final int MANTISSA_BITS = 3, MANTISSA_VALUE = 1 << MANTISSA_BITS, MANTISSA_MASK = MANTISSA_VALUE - 1;
    private static final int TOP_BINS = 32, LEAF_BINS = TOP_BINS * 8, TOP_SHIFT = 3, LEAF_MASK = 7;
    private static final int UNUSED = -1;

    private final int size;
    private int freeStorage;
    private int usedBinsTop;
    private final int[] usedBins = new int[TOP_BINS];
    private final int[] binIndices = new int[LEAF_BINS];

    private int[] dataOffset, dataSize, binPrev, binNext, neighborPrev, neighborNext;
    private boolean[] used;
    private int[] freeNodes;
    private int freeTop;

    public CgOffsetAllocator(int size) {
        if (size <= 0) throw new IllegalArgumentException("size " + size);
        this.size = size;
        Arrays.fill(binIndices, UNUSED);
        nodes(64);
        for (int i = 0; i < 64; i++) freeNodes[i] = 63 - i;
        freeTop = 63;
        insertIntoBin(size, 0);
    }

    /** The whole space, in the caller's unit. */
    public int size() {
        return size;
    }

    /** What is free in total, however split. */
    public int freeStorage() {
        return freeStorage;
    }

    /** A node for {@code count} units, or {@link #NO_SPACE}. */
    public int allocate(int count) {
        if (count <= 0) throw new IllegalArgumentException("allocate " + count);
        int minBin = roundUp(count);
        int minTop = minBin >>> TOP_SHIFT, minLeaf = minBin & LEAF_MASK;
        int top = minTop, leaf = NO_SPACE;
        if ((usedBinsTop & (1 << top)) != 0) leaf = lowestSetBitAfter(usedBins[top], minLeaf);
        if (leaf == NO_SPACE) {
            top = lowestSetBitAfter(usedBinsTop, minTop + 1);
            if (top == NO_SPACE) return NO_SPACE;
            leaf = Integer.numberOfTrailingZeros(usedBins[top]);
        }
        int bin = (top << TOP_SHIFT) | leaf;
        int node = binIndices[bin];
        int total = dataSize[node];
        dataSize[node] = count;
        used[node] = true;
        binIndices[bin] = binNext[node];
        if (binNext[node] != UNUSED) binPrev[binNext[node]] = UNUSED;
        freeStorage -= total;
        if (binIndices[bin] == UNUSED) {
            usedBins[top] &= ~(1 << leaf);
            if (usedBins[top] == 0) usedBinsTop &= ~(1 << top);
        }
        int rest = total - count;
        if (rest > 0) {
            int split = insertIntoBin(rest, dataOffset[node] + count);
            if (neighborNext[node] != UNUSED) neighborPrev[neighborNext[node]] = split;
            neighborPrev[split] = node;
            neighborNext[split] = neighborNext[node];
            neighborNext[node] = split;
        }
        return node;
    }

    /** Where {@code node}'s range starts. */
    public int offset(int node) {
        return dataOffset[node];
    }

    /** The range's length, as allocated. */
    public int count(int node) {
        return dataSize[node];
    }

    /** Frees {@code node}'s range, merged with free ranges either side. */
    public void free(int node) {
        if (!used[node]) throw new IllegalStateException("node " + node + " is not allocated");
        int offset = dataOffset[node], length = dataSize[node];
        int prev = neighborPrev[node];
        if (prev != UNUSED && !used[prev]) {
            offset = dataOffset[prev];
            length += dataSize[prev];
            removeFromBin(prev);
            neighborPrev[node] = neighborPrev[prev];
        }
        int next = neighborNext[node];
        if (next != UNUSED && !used[next]) {
            length += dataSize[next];
            removeFromBin(next);
            neighborNext[node] = neighborNext[next];
        }
        int after = neighborNext[node], before = neighborPrev[node];
        used[node] = false;
        freeNodes[++freeTop] = node;
        int merged = insertIntoBin(length, offset);
        if (after != UNUSED) {
            neighborNext[merged] = after;
            neighborPrev[after] = merged;
        }
        if (before != UNUSED) {
            neighborPrev[merged] = before;
            neighborNext[before] = merged;
        }
    }

    private int insertIntoBin(int length, int offset) {
        if (freeTop < 0) growNodes();
        int bin = roundDown(length);
        int top = bin >>> TOP_SHIFT, leaf = bin & LEAF_MASK;
        if (binIndices[bin] == UNUSED) {
            usedBins[top] |= 1 << leaf;
            usedBinsTop |= 1 << top;
        }
        int head = binIndices[bin];
        int node = freeNodes[freeTop--];
        dataOffset[node] = offset;
        dataSize[node] = length;
        binPrev[node] = UNUSED;
        binNext[node] = head;
        neighborPrev[node] = UNUSED;
        neighborNext[node] = UNUSED;
        used[node] = false;
        if (head != UNUSED) binPrev[head] = node;
        binIndices[bin] = node;
        freeStorage += length;
        return node;
    }

    private void removeFromBin(int node) {
        if (binPrev[node] != UNUSED) {
            binNext[binPrev[node]] = binNext[node];
            if (binNext[node] != UNUSED) binPrev[binNext[node]] = binPrev[node];
        } else {
            int bin = roundDown(dataSize[node]);
            int top = bin >>> TOP_SHIFT, leaf = bin & LEAF_MASK;
            binIndices[bin] = binNext[node];
            if (binNext[node] != UNUSED) binPrev[binNext[node]] = UNUSED;
            if (binIndices[bin] == UNUSED) {
                usedBins[top] &= ~(1 << leaf);
                if (usedBins[top] == 0) usedBinsTop &= ~(1 << top);
            }
        }
        freeNodes[++freeTop] = node;
        freeStorage -= dataSize[node];
    }

    /** Doubles the node storage; the new nodes go on the free stack. */
    private void growNodes() {
        int old = dataOffset.length, count = freeTop + 1;
        nodes(old * 2);
        for (int i = 0; i < old; i++) freeNodes[count + i] = old * 2 - 1 - i;
        freeTop = count + old - 1;
    }

    private void nodes(int capacity) {
        dataOffset = grow(dataOffset, capacity);
        dataSize = grow(dataSize, capacity);
        binPrev = grow(binPrev, capacity);
        binNext = grow(binNext, capacity);
        neighborPrev = grow(neighborPrev, capacity);
        neighborNext = grow(neighborNext, capacity);
        used = used == null ? new boolean[capacity] : Arrays.copyOf(used, capacity);
        freeNodes = grow(freeNodes, capacity);
    }

    private static int[] grow(int[] a, int capacity) {
        return a == null ? new int[capacity] : Arrays.copyOf(a, capacity);
    }

    private static int lowestSetBitAfter(int mask, int start) {
        if (start >= 32) return NO_SPACE;
        int after = mask & ~((1 << start) - 1);
        return after == 0 ? NO_SPACE : Integer.numberOfTrailingZeros(after);
    }

    /** The smallest space that serves a request of {@code count} units: the size of its class. */
    public static int fittingSize(int count) {
        int bin = roundUp(count);
        int exponent = bin >>> MANTISSA_BITS, mantissa = bin & MANTISSA_MASK;
        return exponent == 0 ? mantissa : (mantissa | MANTISSA_VALUE) << (exponent - 1);
    }

    /** The smallest size class every range in which holds {@code size}. */
    static int roundUp(int size) {
        if (size < MANTISSA_VALUE) return size;
        int highest = 31 - Integer.numberOfLeadingZeros(size);
        int start = highest - MANTISSA_BITS;
        int exp = start + 1;
        int mantissa = (size >>> start) & MANTISSA_MASK;
        if ((size & ((1 << start) - 1)) != 0) mantissa++;
        return (exp << MANTISSA_BITS) + mantissa;
    }

    /** The size class a free range of {@code size} files under. */
    static int roundDown(int size) {
        if (size < MANTISSA_VALUE) return size;
        int highest = 31 - Integer.numberOfLeadingZeros(size);
        int start = highest - MANTISSA_BITS;
        return ((start + 1) << MANTISSA_BITS) | ((size >>> start) & MANTISSA_MASK);
    }
}
