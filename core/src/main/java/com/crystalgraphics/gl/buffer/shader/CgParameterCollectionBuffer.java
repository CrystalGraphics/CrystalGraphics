package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * Every parameter collection's values, a vec4 each, read by a material declaring {@code #pragma cg_use globals}:
 * {@code CgParameterCollection}'s storage. Uploaded once a frame by the world renderer; nothing else writes it.
 *
 * <ul>
 *   <li>Render thread only. The class makes its buffer when first touched, after {@code CgGraphicsLifecycle.initContext}.</li>
 * </ul>
 */
public final class CgParameterCollectionBuffer {

    /** The {@code attach()} macro the env file reads values through. */
    public static final String MACRO_NAME = "GLOBAL_DATA";

    public static final CgBufferFormat FORMAT = CgBufferFormat.builder("CgGlobal", STD430).vec4("value").build();

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgGlobals", FORMAT, CgBindingPoints.GLOBALS, CgBufferLifetime.FRAME);

    private CgParameterCollectionBuffer() {
    }

    /** The buffer, for {@code CgEngineBufferRegistry}'s {@code globals} token, by method reference. */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }

    /** Uploads the first {@code vec4s} vec4s of {@code data} as this frame's values. Render thread. */
    public static void upload(float[] data, int vec4s) {
        BUFFER.uploadRaw(data, Math.max(vec4s, 1) * 4);
    }
}
