package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Buffers read as texels: one buffer texture per texture unit, pointed at whatever buffer is read there next. What a
 * material reads a kernel's buffer through below the SSBO path, and what a lowered kernel reads its buffers through.
 * Render thread.
 *
 * <pre>{@code
 * CgBufferTextures.bind(unit, CgGL.GL_RGBA32UI, buffer);   // the program's usamplerBuffer on that unit reads it
 * }</pre>
 *
 * <ul>
 *   <li>Buffer 0 binds sixteen zero bytes, so an unbound read answers zeros.</li>
 *   <li>Leaves {@code unit} the active texture unit.</li>
 *   <li>{@link #releaseAll} at context teardown.</li>
 * </ul>
 */
public final class CgBufferTextures {

    private static int[] textures = new int[0];
    private static int empty;

    private CgBufferTextures() {}

    /** Buffer {@code buffer} at texture unit {@code unit}, read as texels of {@code format} ({@code GL_R32UI}, ...). */
    public static void bind(int unit, int format, int buffer) {
        if (unit >= textures.length) {
            int had = textures.length;
            textures = Arrays.copyOf(textures, unit + 1);
            for (int i = had; i <= unit; i++) textures[i] = CgGL.glGenTextures();
        }
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_BUFFER, textures[unit]);
        CgGL.glTexBuffer(CgGL.GL_TEXTURE_BUFFER, format, buffer == 0 ? empty() : buffer);
    }

    /** Sixteen zero bytes: what an unbound buffer texture reads. */
    private static int empty() {
        if (empty == 0) {
            empty = CgGL.glGenBuffers();
            CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, empty);
            CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()),
                    CgGL.GL_STATIC_DRAW);
            CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        }
        return empty;
    }

    public static void releaseAll() {
        for (int texture : textures) CgGL.glDeleteTextures(texture);
        textures = new int[0];
        if (empty != 0) CgGL.glDeleteBuffers(empty);
        empty = 0;
    }
}
