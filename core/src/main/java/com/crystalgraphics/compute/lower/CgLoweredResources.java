package com.crystalgraphics.compute.lower;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.cpu.CgCpuMirrors;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What every lowered dispatch shares (gpu-compute C5): scratch buffers and texel targets pooled by size, an empty
 * vertex array to draw with, and which counters a fill zeroed this frame. Buffers are read through
 * {@code CgBufferTextures}. Render thread; {@link #releaseAll} at context teardown. Nothing here allocates once its
 * pools hold what a frame uses.
 *
 * <pre>{@code
 * int scratch = CgLoweredResources.scratch(bytes);
 * ... capture appended elements into it, place them ...
 * CgLoweredResources.release(scratch, bytes);
 * }</pre>
 */
public final class CgLoweredResources {

    /** Free scratch buffers and their size classes; a handful at most, so scanned. */
    private static int[] freeScratch = new int[16];
    private static long[] freeScratchSize = new long[16];
    private static int freeScratchCount;
    private static final List<CgTexelTarget> FREE_TARGETS = new ArrayList<>();
    /** Ranges a fill zeroed this frame, as buffer, first byte, end byte: an append counter in one is known zero. */
    private static long[] zeroed = new long[3 * 16];
    private static int zeroedCount;
    private static long zeroedFrame = Long.MIN_VALUE;
    private static int[] created = new int[16];
    private static int createdCount;
    private static int vertexArray, framebuffer, outputs, outputsDrawn;
    /** Per count of targets, the draw-buffer list naming that many attachments. */
    private static final IntBuffer[] DRAW_BUFFERS = new IntBuffer[CgLowering.MAX_TARGETS + 1];
    private static CgTexelTarget count;

    private CgLoweredResources() {}

    // ── Pools ─────────────────────────────────────────────────────────────────

    /** A buffer of at least {@code bytes}, from the pool: its size class is the next power of two. */
    public static int scratch(long bytes) {
        long size = sizeClass(bytes);
        for (int i = 0; i < freeScratchCount; i++) {
            if (freeScratchSize[i] != size) continue;
            int buffer = freeScratch[i];
            freeScratchCount--;
            freeScratch[i] = freeScratch[freeScratchCount];
            freeScratchSize[i] = freeScratchSize[freeScratchCount];
            return buffer;
        }
        int buffer = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, size, CgGL.GL_DYNAMIC_COPY);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        if (createdCount == created.length) created = Arrays.copyOf(created, created.length * 2);
        created[createdCount++] = buffer;
        return buffer;
    }

    public static void release(int buffer, long bytes) {
        if (freeScratchCount == freeScratch.length) {
            freeScratch = Arrays.copyOf(freeScratch, freeScratch.length * 2);
            freeScratchSize = Arrays.copyOf(freeScratchSize, freeScratchSize.length * 2);
        }
        freeScratch[freeScratchCount] = buffer;
        freeScratchSize[freeScratchCount++] = sizeClass(bytes);
    }

    private static long sizeClass(long bytes) {
        return Math.max(256, Long.highestOneBit(Math.max(1, bytes) - 1) << 1);
    }

    /** A {@code width} x {@code height} target of {@code type}, from the pool. */
    public static CgTexelTarget target(CgTextureType type, int width, int height) {
        for (int i = 0; i < FREE_TARGETS.size(); i++) {
            CgTexelTarget t = FREE_TARGETS.get(i);
            if (t.type() != type || t.width() != width || t.height() != height) continue;
            int last = FREE_TARGETS.size() - 1;
            FREE_TARGETS.set(i, FREE_TARGETS.get(last));
            FREE_TARGETS.remove(last);
            return t;
        }
        return CgTexelTarget.create(type, width, height);
    }

    public static void release(CgTexelTarget target) {
        FREE_TARGETS.add(target);
    }

    /** The 1x1 float target appended elements are counted into. */
    public static CgTexelTarget count() {
        if (count == null) count = CgTexelTarget.create(CgTextureType.R32F, 1, 1);
        return count;
    }

    /** An empty vertex array, for draws whose stages read no attributes. */
    public static int vertexArray() {
        if (vertexArray == 0) vertexArray = CgGL.glGenVertexArrays();
        return vertexArray;
    }

    /** A framebuffer images are attached to, a level or a layer at a time. */
    public static int framebuffer() {
        if (framebuffer == 0) framebuffer = CgGL.glGenFramebuffers();
        return framebuffer;
    }

    /**
     * Binds the framebuffer an output pass attaches its targets to, drawing into its first {@code targets}
     * attachments. The caller detaches what it attached.
     */
    public static void bindOutputs(int targets) {
        if (outputs == 0) outputs = CgGL.glGenFramebuffers();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, outputs);
        if (outputsDrawn == targets) return;
        if (DRAW_BUFFERS[targets] == null) {
            IntBuffer list = CgBufferUtils.createIntBuffer(targets);
            for (int n = 0; n < targets; n++) list.put(n, CgGL.GL_COLOR_ATTACHMENT0 + n);
            DRAW_BUFFERS[targets] = list;
        }
        CgGL.glDrawBuffers(DRAW_BUFFERS[targets]);
        outputsDrawn = targets;
    }

    // ── Counters a fill zeroed ────────────────────────────────────────────────

    /** Bytes {@code [offset, offset + size)} of {@code buffer} hold zero, a fill having written them in {@code frame}. */
    public static void zeroed(int buffer, long offset, long size, long frame) {
        CgCpuMirrors.forget(buffer);
        if (frame != zeroedFrame) {
            zeroedCount = 0;
            zeroedFrame = frame;
        }
        if (zeroedCount * 3 == zeroed.length) zeroed = Arrays.copyOf(zeroed, zeroed.length * 2);
        zeroed[zeroedCount * 3] = buffer;
        zeroed[zeroedCount * 3 + 1] = offset;
        zeroed[zeroedCount * 3 + 2] = offset + size;
        zeroedCount++;
    }

    /**
     * Something other than a fill of zero wrote {@code buffer} on the GPU, or freed it: none of its words is known to be
     * zero, and a CPU tier copy of it is dropped.
     */
    public static void written(int buffer) {
        CgCpuMirrors.forget(buffer);
        for (int i = zeroedCount - 1; i >= 0; i--) {
            if (zeroed[i * 3] != buffer) continue;
            zeroedCount--;
            System.arraycopy(zeroed, zeroedCount * 3, zeroed, i * 3, 3);
        }
    }

    /** Whether the word at {@code offset} in {@code buffer} is known to hold zero, in frame {@code frame}. */
    public static boolean isZero(int buffer, long offset, long frame) {
        if (frame != zeroedFrame) return false;
        for (int i = 0; i < zeroedCount; i++) {
            if (zeroed[i * 3] == buffer && offset >= zeroed[i * 3 + 1] && offset + 4 <= zeroed[i * 3 + 2]) return true;
        }
        return false;
    }

    /** Every buffer, texture and framebuffer here, and the helper programs. At context teardown. */
    public static void releaseAll() {
        for (int i = 0; i < createdCount; i++) CgGL.glDeleteBuffers(created[i]);
        createdCount = 0;
        freeScratchCount = 0;
        for (CgTexelTarget t : FREE_TARGETS) t.delete();
        FREE_TARGETS.clear();
        zeroedCount = 0;
        if (count != null) count.delete();
        count = null;
        if (vertexArray != 0) CgGL.glDeleteVertexArrays(vertexArray);
        if (framebuffer != 0) CgGL.glDeleteFramebuffers(framebuffer);
        if (outputs != 0) CgGL.glDeleteFramebuffers(outputs);
        vertexArray = 0;
        framebuffer = 0;
        outputs = 0;
        outputsDrawn = 0;
        CgLoweredPrograms.releaseAll();
    }
}
