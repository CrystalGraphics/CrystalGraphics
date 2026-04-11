package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;

import java.util.HashMap;
import java.util.Map;

/**
 * Global registry of shared vertex input bindings keyed by vertex format.
 */
public final class CgVertexArrayRegistry {

    public static final CgVertexArrayRegistry INSTANCE = new CgVertexArrayRegistry();

    private static final int INITIAL_QUADS_PER_FORMAT = 4096;

    /**
     * Global cache of all the VAO/VBO bindings to their vertex formats 
     */
    private final Map<CgVertexFormat, CgVertexArrayBinding> bindings = new HashMap<>();

    
    private CgVertexArrayRegistry() {
    }

    public static CgVertexArrayRegistry get() {
        return INSTANCE;
    }

    public CgVertexArrayBinding getOrCreate(CgVertexFormat format) {
        CgVertexArrayBinding existing = bindings.get(format);
        if (existing != null) {
            return existing;
        }

        // VBO must be bound *before* VAO configure so that glVertexAttribPointer
        // captures the VBO binding into the VAO state.
        CgStreamBuffer streamBuffer = CgStreamBuffer.create(format.getStride() * 4 * INITIAL_QUADS_PER_FORMAT);
        streamBuffer.bind();
        CgVertexArray vertexArray = CgVertexArray.create();
        vertexArray.configure(format);
        streamBuffer.unbind();
        vertexArray.unbind();

        CgVertexArrayBinding binding = new CgVertexArrayBinding(format, streamBuffer, vertexArray);
        bindings.put(format, binding);
        return binding;
    }

    public void deleteAll() {
        for (CgVertexArrayBinding binding : bindings.values())
            binding.delete();

        bindings.clear();
    }
}
