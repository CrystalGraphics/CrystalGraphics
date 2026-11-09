package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads a buffer's words back now, waiting for the GPU. Every read is a stall, counted as {@code buffer.readbacks} and
 * {@code buffer.readback-bytes}. Render thread, on a device that owns its submission: a hosted one refuses to wait.
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

    private static ByteBuffer scratch = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());

    private CgBufferReadback() {}

    /** {@code count} words at byte {@code offset} in buffer {@code name}, into {@code into} from index {@code at}. */
    public static void readWords(int name, long offset, int[] into, int at, int count) {
        if (count == 0) return;
        int bytes = count * 4;
        CgTrace.add(CgChannels.GL, READBACKS, 1);
        CgTrace.add(CgChannels.GL, READBACK_BYTES, bytes);
        if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Math.max(bytes, scratch.capacity() * 2)).order(ByteOrder.nativeOrder());
        scratch.clear().limit(bytes);
        CgGLImports imports = CgGLImports.get();
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().readBuffer(imports.buffer(name), imports.bufferOffset(name) + offset, scratch);
        } finally {
            CgDeviceSection.end();
        }
        for (int i = 0; i < count; i++) into[at + i] = scratch.getInt(i * 4);
    }

    /** At the device's teardown: nothing of the device is kept. */
    public static void release() {}
}
