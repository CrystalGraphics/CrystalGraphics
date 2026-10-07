package com.crystalgraphics.compute.ops;

import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Many sets of instances culled together by
 * {@link CgGpuOps#cull(CgComputePass, CgCull, CgCullSets, CgGraphBuffer, CgGraphBuffer)}: a few dispatches however
 * many sets, where a cull per set costs a few per set. Each set is what a {@link CgCull} holds when it is {@linkplain #add added}; the view and the pyramid
 * are the cull handed to the op. Mutable, and reused frame to frame without allocating once grown.
 *
 * <pre>{@code
 * CgCullSets sets = new CgCullSets();                                  // once
 * // each frame:
 * sets.clear();
 * int rocks = sets.add(cull.mesh(rockLods).place(rockPlace), rockRecords, 0, CgGpuCount.of(n));
 * int trees = sets.add(cull.mesh(treeLods).place(treePlace), treeRecords, 0, CgGpuCount.at(alive, 0, m));
 * CgGpuOps.cull(pass, cull.view(view, projection).pyramid(depth), sets, visible, counts);
 * for (int l = 0; l < rockLods.levelCount(); l++) {
 *     chunks.draw(pipeline, bindings, rockLods.level(l)).objects(visible, sets.first(rocks) + CgGpuOps.cullFirst(l, n), n)
 *           .indirect(counts, (sets.word(rocks) + l) * 4L, CgIndirect.INSTANCES, 1);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code visible} holds {@link #records()} records and {@code counts} {@link #words()} words, with {@code INDIRECT}
 *       for the draws.</li>
 *   <li>{@link #first} and {@link #word} are known once the op has run: sets sharing a buffer are laid out together.</li>
 *   <li>Every set's records keep the order they are in, on every tier.</li>
 * </ul>
 */
public final class CgCullSets {

    /** A set's row of {@code cull.compute}'s {@code SETS}: sixteen {@code vec4}s, then two {@code ivec4}s. */
    static final int ROW_FLOATS = 64, ROW_INTS = 8, ROW_BYTES = (ROW_FLOATS + ROW_INTS) * 4;

    private int count;
    private float[] floats = new float[ROW_FLOATS * 16];
    /** Per set, as added: levels (0 for none by size), customs stamped, the count's word (-1: none), capacity, first. */
    private int[] levels = new int[16], customs = new int[16], countWords = new int[16], capacities = new int[16],
            firsts = new int[16], groupOf = new int[16];
    /** Per set, once laid out: its row, its first output record, its first key and output record in its group's. */
    private int[] rows = new int[16], outFirsts = new int[16], keyAt = new int[16], outAt = new int[16];
    private CgGraphBuffer[] groupInstances = new CgGraphBuffer[4], groupCounts = new CgGraphBuffer[4];
    private int groups;
    private ByteBuffer staging = ByteBuffer.allocate(0);
    private int[] laid = new int[24];
    private CgGraphBuffer rowsBuffer;

    /** Forgets every set: the start of a frame's. */
    public CgCullSets clear() {
        count = 0;
        groups = 0;
        Arrays.fill(groupInstances, null);
        Arrays.fill(groupCounts, null);
        return this;
    }

    /**
     * Adds the set {@code cull} describes now, of the count's records of {@code instances} from record {@code first},
     * and answers its index. The cull's view and pyramid are not taken: the op's cull gives them.
     */
    public int add(CgCull cull, CgGraphBuffer instances, int first, CgGpuCount count) {
        if (first < 0 || (long) (first + count.capacity()) * CgGpuOps.cullRecordBytes() > instances.size()) {
            throw new IllegalArgumentException("records " + first + " to " + (first + count.capacity()) + " of " + instances
                    + ", which holds " + instances.size() / CgGpuOps.cullRecordBytes());
        }
        int s = this.count++;
        grow(this.count);
        float[] f = floats;
        int at = s * ROW_FLOATS;
        Matrix4f p = cull.place;
        Matrix3f n = cull.placeNormal;
        f[at] = p.m00(); f[at + 1] = p.m01(); f[at + 2] = p.m02(); f[at + 3] = p.m03();
        f[at + 4] = p.m10(); f[at + 5] = p.m11(); f[at + 6] = p.m12(); f[at + 7] = p.m13();
        f[at + 8] = p.m20(); f[at + 9] = p.m21(); f[at + 10] = p.m22(); f[at + 11] = p.m23();
        f[at + 12] = p.m30(); f[at + 13] = p.m31(); f[at + 14] = p.m32(); f[at + 15] = p.m33();
        f[at + 16] = n.m00(); f[at + 17] = n.m01(); f[at + 18] = n.m02(); f[at + 19] = 0f;
        f[at + 20] = n.m10(); f[at + 21] = n.m11(); f[at + 22] = n.m12(); f[at + 23] = 0f;
        f[at + 24] = n.m20(); f[at + 25] = n.m21(); f[at + 26] = n.m22(); f[at + 27] = 0f;
        float[] box = cull.box;
        f[at + 28] = box[0] - cull.pad; f[at + 29] = box[1] - cull.pad; f[at + 30] = box[2] - cull.pad; f[at + 31] = cull.scale;
        f[at + 32] = box[3] + cull.pad; f[at + 33] = box[4] + cull.pad; f[at + 34] = box[5] + cull.pad; f[at + 35] = cull.normalScale;
        System.arraycopy(cull.heights, 0, f, at + 36, CgCull.MAX_LEVELS);
        f[at + 44] = cull.lightBlock; f[at + 45] = cull.lightSky; f[at + 46] = cull.stampLight ? 1f : 0f; f[at + 47] = 0f;
        System.arraycopy(cull.customs, 0, f, at + 48, 16);
        levels[s] = cull.levels;
        customs[s] = cull.stampCustoms;
        countWords[s] = count.onGpu() ? count.word() : -1;
        capacities[s] = count.capacity();
        firsts[s] = first;
        groupOf[s] = group(instances, count.onGpu() ? count.buffer() : null);
        return s;
    }

    /** How many sets were added. */
    public int size() {
        return count;
    }

    /** The output records every set's levels take: what {@code visible} holds. */
    public int records() {
        int n = 0;
        for (int s = 0; s < count; s++) n += CgGpuOps.cullFirst(Math.max(1, levels[s]), capacities[s]);
        return n;
    }

    /** The words of counts: {@link CgCull#MAX_LEVELS} a set, and one past them for what no level kept. */
    public int words() {
        return count * CgCull.MAX_LEVELS + 1;
    }

    /** Where set {@code set}'s level 0 starts in the output: level l's at {@code first + cullFirst(l, capacity)}. */
    public int first(int set) {
        return outFirsts[set];
    }

    /** Set {@code set}'s level 0 count's word in counts: level l's at {@code word + l}. */
    public int word(int set) {
        return rows[set] * CgCull.MAX_LEVELS;
    }

    // ── For the op ──────────────────────────────────────────────────────────

    int groups() {
        return groups;
    }

    CgGraphBuffer groupInstances(int g) {
        return groupInstances[g];
    }

    /** Group {@code g}'s count buffer, or null when its sets' counts are fixed. */
    CgGraphBuffer groupCounts(int g) {
        return groupCounts[g];
    }

    /**
     * Lays the sets out group by group, rows in that order: per group its first row, its rows, its first key (a multiple
     * of {@code keyAlign}), its keys, its first output record and its output records, read by {@link #laid}. Answers
     * the keys in all.
     */
    int layout(int keyAlign) {
        if (laid.length < groups * 6) laid = new int[groups * 12];
        int[] group = laid;
        int row = 0, key = 0, out = 0;
        for (int g = 0; g < groups; g++) {
            int at = g * 6, rowsIn = 0, keys = 0, outs = 0;
            group[at] = row;
            key = (key + keyAlign - 1) / keyAlign * keyAlign;
            group[at + 2] = key;
            group[at + 4] = out;
            for (int s = 0; s < count; s++) {
                if (groupOf[s] != g) continue;
                rows[s] = row++;
                rowsIn++;
                keyAt[s] = keys;
                outAt[s] = outs;
                outFirsts[s] = out + outs;
                keys += capacities[s];
                outs += CgGpuOps.cullFirst(Math.max(1, levels[s]), capacities[s]);
            }
            group[at + 1] = rowsIn;
            group[at + 3] = (keys + keyAlign - 1) / keyAlign * keyAlign;
            group[at + 5] = outs;
            key += group[at + 3];
            out += outs;
        }
        return key;
    }

    /** Group {@code g}'s value {@code k} of {@link #layout}'s six. */
    int laid(int g, int k) {
        return laid[g * 6 + k];
    }

    /** A buffer holding the rows: this set's own, made again only to grow. */
    CgGraphBuffer rowsBuffer() {
        long bytes = Math.max(1, count) * (long) ROW_BYTES;
        if (rowsBuffer == null || rowsBuffer.size() < bytes) {
            rowsBuffer = CgGraphBuffer.transientBuffer("ops.cull.sets",
                    CgBufferDesc.of(Long.highestOneBit(bytes - 1) << 1, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        return rowsBuffer;
    }

    /** The rows' bytes, in row order, after {@link #layout}. */
    ByteBuffer rows() {
        int bytes = count * ROW_BYTES;
        if (staging.capacity() < bytes) staging = ByteBuffer.allocate(Math.max(bytes, staging.capacity() * 2)).order(ByteOrder.nativeOrder());
        staging.clear();
        for (int s = 0; s < count; s++) {
            int at = rows[s] * ROW_BYTES;
            for (int w = 0; w < ROW_FLOATS; w++) staging.putFloat(at + w * 4, floats[s * ROW_FLOATS + w]);
            at += ROW_FLOATS * 4;
            staging.putInt(at, levels[s]).putInt(at + 4, customs[s]).putInt(at + 8, countWords[s]).putInt(at + 12, capacities[s]);
            staging.putInt(at + 16, firsts[s]).putInt(at + 20, keyAt[s]).putInt(at + 24, outAt[s]).putInt(at + 28, 0);
        }
        staging.limit(bytes);
        return staging;
    }

    private int group(CgGraphBuffer instances, CgGraphBuffer counts) {
        for (int g = 0; g < groups; g++) if (groupInstances[g] == instances && groupCounts[g] == counts) return g;
        if (groups == groupInstances.length) {
            groupInstances = Arrays.copyOf(groupInstances, groups * 2);
            groupCounts = Arrays.copyOf(groupCounts, groups * 2);
        }
        groupInstances[groups] = instances;
        groupCounts[groups] = counts;
        return groups++;
    }

    private void grow(int n) {
        if (n <= levels.length) return;
        int size = Math.max(n, levels.length * 2);
        floats = Arrays.copyOf(floats, size * ROW_FLOATS);
        levels = Arrays.copyOf(levels, size);
        customs = Arrays.copyOf(customs, size);
        countWords = Arrays.copyOf(countWords, size);
        capacities = Arrays.copyOf(capacities, size);
        firsts = Arrays.copyOf(firsts, size);
        groupOf = Arrays.copyOf(groupOf, size);
        rows = Arrays.copyOf(rows, size);
        outFirsts = Arrays.copyOf(outFirsts, size);
        keyAt = Arrays.copyOf(keyAt, size);
        outAt = Arrays.copyOf(outAt, size);
    }
}
