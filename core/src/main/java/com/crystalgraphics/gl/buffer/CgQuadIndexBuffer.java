package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ShortBuffer;

/**
 * Globally shared quad index buffer. Pattern: [0,1,2, 2,3,0, 4,5,6, 6,7,4, …]
 * using {@code GL_UNSIGNED_SHORT}.
 *
 * <p>Created lazily on first use. Grows with doubling strategy. Never shrinks.
 * Shared by ALL quad renderers (text, UI, sprites). Maximum capacity: 16384
 * quads (65536/4 vertices, GL_UNSIGNED_SHORT limit).</p>
 */
public final class CgQuadIndexBuffer {

    public static final int MAX_QUADS = 16384;
    private static CgQuadIndexBuffer instance;

    private int glBuffer;
    private int currentCapacityQuads;

    private CgQuadIndexBuffer() {
    }

    public static CgQuadIndexBuffer get() {
        if (instance == null) instance = new CgQuadIndexBuffer();
        
        return instance;
    }

    /**
     * Binds the IBO to {@code GL_ELEMENT_ARRAY_BUFFER} and ensures it can hold
     * at least {@code neededQuads} worth of indices. Grows if necessary.
     */
    public void bindAndEnsureCapacity(int neededQuads) {
        if (glBuffer == 0) glBuffer = CgGL.glGenBuffers();
        
        bind();
        if (neededQuads > currentCapacityQuads) grow(neededQuads);
    }

    public void bind() {
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, glBuffer);
    }
    
    public void unbind() {
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public static void freeAll() {
        if (instance != null && instance.glBuffer != 0) {
            CgGL.glDeleteBuffers(instance.glBuffer);
            instance.glBuffer = 0;
            instance = null;
        }
    }

    private void grow(int neededQuads) {
        int newCapacity = Math.min(
                MAX_QUADS,
                Math.max(neededQuads, currentCapacityQuads * 2)
        );
        if (newCapacity < 256) newCapacity = 256;

        ShortBuffer indices = CgBufferUtils.createShortBuffer(newCapacity * 6);
        for (int i = 0; i < newCapacity; i++) {
            short base = (short) (i * 4);
            indices.put(base);
            indices.put((short) (base + 1));
            indices.put((short) (base + 2));
            indices.put((short) (base + 2));
            indices.put((short) (base + 3));
            indices.put(base);
        }
        indices.flip();

        CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, indices, CgGL.GL_STATIC_DRAW);
        currentCapacityQuads = newCapacity;
    }
}
