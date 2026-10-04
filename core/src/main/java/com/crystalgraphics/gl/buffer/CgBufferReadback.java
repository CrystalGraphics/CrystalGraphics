package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads a GL buffer's words back now, through a buffer of its own: a buffer made for the GPU need not be mappable
 * (immutable storage without {@code GL_MAP_READ_BIT}). Every read is a stall, counted as {@code buffer.readbacks} and
 * {@code buffer.readback-bytes}: what the compute tiers below indirect draws, and the CPU tier, pay. Render thread.
 *
 * <pre>{@code
 * int[] count = new int[1];
 * CgBufferReadback.readWords(counts, 12, count, 0, 1);
 * }</pre>
 *
 * <p>{@link CgReadback} reads without a stall, delivered frames later; this is for what is needed now.</p>
 */
public final class CgBufferReadback {

    private static final int READBACKS = CgTrace.name("buffer.readbacks");
    private static final int READBACK_BYTES = CgTrace.name("buffer.readback-bytes");

    private static int buffer;
    private static long size;
    private static ByteBuffer view;

    private CgBufferReadback() {}

    /** {@code count} words at byte {@code offset} in GL buffer {@code name}, into {@code into} from index {@code at}. */
    public static void readWords(int name, long offset, int[] into, int at, int count) {
        if (count == 0) return;
        long bytes = count * 4L;
        CgTrace.add(CgChannels.GL, READBACKS, 1);
        CgTrace.add(CgChannels.GL, READBACK_BYTES, bytes);
        if (bytes > size) {
            if (buffer == 0) buffer = CgGL.glGenBuffers();
            size = Math.max(bytes, size * 2);
            CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
            CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, size, CgGL.GL_STREAM_READ);
        }
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, name);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, offset, 0, bytes);
        ByteBuffer mapped = CgGL.glMapBufferRange(CgGL.GL_COPY_WRITE_BUFFER, 0, bytes, CgGL.GL_MAP_READ_BIT, view);
        if (mapped == null) throw new IllegalStateException("a read-back of " + bytes + " bytes could not be mapped");
        view = mapped;
        mapped.order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) into[at + i] = mapped.getInt(i * 4);
        CgGL.glUnmapBuffer(CgGL.GL_COPY_WRITE_BUFFER);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
    }

    /** The buffer reads go through. At context teardown. */
    public static void release() {
        if (buffer != 0) CgGL.glDeleteBuffers(buffer);
        buffer = 0;
        size = 0;
        view = null;
    }
}
