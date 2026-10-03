package com.crystalgraphics.compute.cpu;

import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The CPU tier's copies of GL buffers, by GL name: read back the first time a body needs one, written by bodies and
 * uploaded through the frame ring at once, so the copy and the GPU's buffer stay equal. A copy lives until something
 * else writes the buffer ({@link #forget}): the frame graph's executor says so for its fills, updates, copies and frees,
 * and a copy of a buffer the graph does not own goes at the end of every frame ({@link #endFrame}). Render thread.
 *
 * <pre>{@code
 * int[] words = CgCpuMirrors.words(buffer, bytes);   // read back once, then kept
 * ... a body writes words ...
 * CgCpuMirrors.upload(buffer, firstWord, count);
 * }</pre>
 */
public final class CgCpuMirrors {

    private static final int UPLOAD_BYTES = CgTrace.name("compute.cpu-upload-bytes");
    /** GL names are small and dense in practice: indexed directly below this, in a map above it. */
    private static final int DIRECT = 1 << 16;

    private static final class Copy {
        int[] words = new int[0];
        int held;
    }

    private static Copy[] direct = new Copy[256];
    private static final Map<Integer, Copy> SPARSE = new HashMap<>();
    private static int[] externals = new int[16];
    private static int externalCount;
    private static CgStreamBuffer staging;

    private CgCpuMirrors() {}

    /** The CPU copy of GL buffer {@code name}, holding at least its first {@code bytes}: what it lacks is read back. */
    public static int[] words(int name, long bytes) {
        Copy copy = copy(name, true);
        int need = (int) ((bytes + 3) >>> 2);
        if (need > copy.held) {
            if (need > copy.words.length) copy.words = Arrays.copyOf(copy.words, Math.max(need, copy.words.length * 2));
            CgBufferReadback.readWords(name, copy.held * 4L, copy.words, copy.held, need - copy.held);
            copy.held = need;
        }
        return copy.words;
    }

    /** Something outside the frame graph may write GL buffer {@code name}: its copy lasts until this frame ends. */
    public static void external(int name) {
        if (externalCount == externals.length) externals = Arrays.copyOf(externals, externalCount * 2);
        externals[externalCount++] = name;
    }

    /** Words {@code [first, first + count)} of {@code name}'s copy, uploaded to the GPU's buffer through the frame ring. */
    public static void upload(int name, int first, int count) {
        if (count <= 0) return;
        Copy copy = copy(name, false);
        if (copy == null) throw new IllegalStateException("buffer " + name + " has no CPU copy to upload");
        int bytes = count * 4;
        if (staging == null) staging = CgStreamBuffer.create(CgGL.GL_COPY_READ_BUFFER, Math.max(bytes, 1 << 16));
        ByteBuffer out = staging.map(bytes).order(ByteOrder.nativeOrder());
        int[] words = copy.words;
        for (int i = 0; i < count; i++) out.putInt(i * 4, words[first + i]);
        int at = staging.commit(bytes);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, staging.getGlBufferId());
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, name);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, at, first * 4L, bytes);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        CgTrace.add(CgChannels.GL, UPLOAD_BYTES, bytes);
    }

    /** The {@code uint} at byte {@code offset} of {@code name}'s copy, or -1 where the CPU holds none: a count with no read. */
    public static long word(int name, long offset) {
        Copy copy = copy(name, false);
        int at = (int) (offset >>> 2);
        return copy == null || at >= copy.held ? -1 : Integer.toUnsignedLong(copy.words[at]);
    }

    /** Something other than a body wrote GL buffer {@code name}, or freed it: its copy is dropped. */
    public static void forget(int name) {
        Copy copy = copy(name, false);
        if (copy != null) copy.held = 0;
    }

    /** After an executed frame: the copies of buffers written outside the frame graph are dropped. */
    public static void endFrame() {
        for (int i = 0; i < externalCount; i++) forget(externals[i]);
        externalCount = 0;
    }

    /** Every copy, and the staging. At context teardown. */
    public static void releaseAll() {
        Arrays.fill(direct, null);
        SPARSE.clear();
        externalCount = 0;
        if (staging != null) staging.delete();
        staging = null;
    }

    private static Copy copy(int name, boolean make) {
        if (name > 0 && name < DIRECT) {
            if (name >= direct.length) {
                if (!make) return null;
                direct = Arrays.copyOf(direct, Math.min(DIRECT, Math.max(name + 1, direct.length * 2)));
            }
            Copy copy = direct[name];
            if (copy == null && make) copy = direct[name] = new Copy();
            return copy;
        }
        Copy copy = SPARSE.get(name);
        if (copy == null && make) SPARSE.put(name, copy = new Copy());
        return copy;
    }
}
