package com.crystalgraphics.compute;

import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Buffers a GPU check fills from the CPU, known to the graph by the name the imports give them
 * ({@code CgGraphBuffer.imported}). Render thread.
 *
 * <pre>{@code
 * int counts = CgCheckBuffers.words(new int[] {0, 0, 0});
 * CgGraphBuffer counted = CgGraphBuffer.imported("counts", counts, 12);
 * // ... dispatch, read back ...
 * CgCheckBuffers.release(counts);
 * }</pre>
 */
public final class CgCheckBuffers {

    private static final Map<Integer, CgGpuBuffer> MADE = new HashMap<>();

    private CgCheckBuffers() {
    }

    /** A device buffer holding {@code words}, by its name. */
    public static int words(int[] words) {
        ByteBuffer data = ByteBuffer.allocateDirect(Math.max(4, words.length * 4)).order(ByteOrder.nativeOrder());
        for (int w : words) data.putInt(w);
        data.flip();
        return of(data);
    }

    /** A device buffer holding {@code data}'s remaining bytes (4 at least), by its name. */
    public static int of(ByteBuffer data) {
        CgGpuBuffer buffer = CgRHI.get().createBuffer(new CgGpuBuffer.Desc("check", Math.max(4, data.remaining()),
                CgGpuBuffer.Usage.ALL, false));
        if (data.remaining() > 0) {
            CgDeviceSection.begin();
            try {
                CgRHI.get().encoder().writeBuffer(buffer, 0, data);
            } finally {
                CgDeviceSection.end();
            }
        }
        int name = CgGLImports.get().name(buffer);
        MADE.put(name, buffer);
        return name;
    }

    /** Releases a buffer {@link #of} made, destroyed once the frames reading it retire. */
    public static void release(int name) {
        CgGpuBuffer buffer = MADE.remove(name);
        if (buffer == null) return;
        CgGLImports.get().forget(buffer);
        CgRHI.get().release(buffer);
    }
}
