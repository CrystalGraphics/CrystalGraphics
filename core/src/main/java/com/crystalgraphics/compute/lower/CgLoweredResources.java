package com.crystalgraphics.compute.lower;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgDispatchBindings;
import com.crystalgraphics.compute.cpu.CgCpuMirrors;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;

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

    // ── Outputs held in their targets ─────────────────────────────────────────

    /**
     * Buffers whose newest words are still in a target: what a frame graph's lowered dispatch wrote, read back only when
     * something other than a lowered kernel's own reads needs them. Buffer, target, first byte and texels, in parallel;
     * a few at most, so scanned.
     */
    private static int[] residentBuffer = new int[8];
    private static CgTexelTarget[] residentTarget = new CgTexelTarget[8];
    private static long[] residentAt = new long[8], residentTexels = new long[8];
    private static int residentCount;
    private static final int LANDINGS = CgTrace.name("compute.lowered-landings");
    private static final int HELD = CgTrace.name("compute.lowered-held");

    /**
     * Target {@code t}'s first {@code texels} are {@code buffer}'s words from byte {@code at}, kept there until
     * {@link #land} reads them back; this owns {@code t} from here. What the buffer held in a target before lands first,
     * unless this span covers it.
     */
    static void hold(CgTexelTarget t, int buffer, long at, long texels) {
        written(buffer);
        int i = residentIndex(buffer);
        if (i >= 0) {
            long end = residentAt[i] + residentTexels[i] * texelBytes(residentTarget[i]);
            if (at <= residentAt[i] && at + texels * texelBytes(t) >= end) {
                release(residentTarget[i]);
                remove(i);
            } else {
                landAt(i);
            }
        }
        if (residentCount == residentBuffer.length) {
            residentBuffer = Arrays.copyOf(residentBuffer, residentCount * 2);
            residentTarget = Arrays.copyOf(residentTarget, residentCount * 2);
            residentAt = Arrays.copyOf(residentAt, residentCount * 2);
            residentTexels = Arrays.copyOf(residentTexels, residentCount * 2);
        }
        residentBuffer[residentCount] = buffer;
        residentTarget[residentCount] = t;
        residentAt[residentCount] = at;
        residentTexels[residentCount] = texels;
        residentCount++;
        CgTrace.add(CgChannels.GL, HELD, 1);
    }

    /** Whether any buffer's words are held in a target. */
    public static boolean holding() {
        return residentCount > 0;
    }

    /** The index of {@code buffer}'s held span, or -1: what a lowered pass reads it through. */
    static int residentIndex(int buffer) {
        for (int i = 0; i < residentCount; i++) if (residentBuffer[i] == buffer) return i;
        return -1;
    }

    static CgTexelTarget residentTarget(int i) {
        return residentTarget[i];
    }

    /** Span {@code i}'s first byte. */
    static long residentAt(int i) {
        return residentAt[i];
    }

    static long residentTexels(int i) {
        return residentTexels[i];
    }

    /** {@code buffer}'s held words read back into it, if a target holds them. */
    public static void land(int buffer) {
        int i = residentIndex(buffer);
        if (i < 0) return;
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO)) {
            landAt(i);
        }
    }

    /** Every buffer, counter and argument buffer {@code b} binds, read back where a target holds it: before anything but a lowered kernel's own reads. */
    public static void land(CgDispatchBindings b) {
        if (residentCount == 0) return;
        for (int i = 0; i < b.buffers(); i++) {
            land(b.buffer(i));
            land(b.counter(i));
        }
        if (b.isIndirect()) land(b.args());
    }

    /** Every held span read back: at the end of the frame graph execution that made them, and before what nothing tracks. */
    public static void landAll() {
        if (residentCount == 0) return;
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO)) {
            while (residentCount > 0) landAt(residentCount - 1);
        }
    }

    /** Span {@code i}'s target, owned by the caller from here: no longer held, and never landed. */
    static CgTexelTarget take(int i) {
        CgTexelTarget t = residentTarget[i];
        remove(i);
        return t;
    }

    /** {@code buffer} is freed or nothing will read it: what a target holds of it is dropped unread. */
    public static void drop(int buffer) {
        int i = residentIndex(buffer);
        if (i < 0) return;
        release(residentTarget[i]);
        remove(i);
    }

    private static void landAt(int i) {
        CgTexelTarget t = residentTarget[i];
        int buffer = residentBuffer[i];
        long at = residentAt[i], texels = residentTexels[i];
        remove(i);
        land(t, buffer, at, texels);
        release(t);
    }

    private static void remove(int i) {
        residentCount--;
        residentBuffer[i] = residentBuffer[residentCount];
        residentTarget[i] = residentTarget[residentCount];
        residentAt[i] = residentAt[residentCount];
        residentTexels[i] = residentTexels[residentCount];
        residentTarget[residentCount] = null;
    }

    /**
     * Target {@code t}'s first {@code texels} read into {@code buffer} from byte {@code at} on the GPU, whole rows then
     * what is left of the last. A target of more than one row is the widest texture, so a row's bytes are a multiple of 8
     * and no pack alignment pads them; the pack row length and skips are the host's zeros. Leaves its read framebuffer
     * bound.
     */
    static void land(CgTexelTarget t, int buffer, long at, long texels) {
        int words = texelWords(t);
        int format = words == 1 ? CgGL.GL_RED_INTEGER : words == 2 ? CgGL.GL_RG_INTEGER : CgGL.GL_RGBA_INTEGER;
        long row = (long) t.width() * words * 4;
        int rows = (int) (texels / t.width()), rest = (int) (texels % t.width());
        CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, t.framebuffer());
        CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, buffer);
        if (rows > 0) CgGL.glReadPixels(0, 0, t.width(), rows, format, CgGL.GL_UNSIGNED_INT, at);
        if (rest > 0) CgGL.glReadPixels(0, rows, rest, 1, format, CgGL.GL_UNSIGNED_INT, at + rows * row);
        CgGL.glBindBuffer(CgGL.GL_PIXEL_PACK_BUFFER, 0);
        written(buffer);
        CgTrace.add(CgChannels.GL, LANDINGS, 1);
    }

    /** Words in one texel of {@code t}. */
    static int texelWords(CgTexelTarget t) {
        return t.type() == CgTextureType.R32UI ? 1 : t.type() == CgTextureType.RG32UI ? 2 : 4;
    }

    private static long texelBytes(CgTexelTarget t) {
        return texelWords(t) * 4L;
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
        while (residentCount > 0) {
            residentTarget[residentCount - 1].delete();
            remove(residentCount - 1);
        }
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
