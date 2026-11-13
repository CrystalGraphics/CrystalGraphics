package com.crystalgraphics.material;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferLayout.MemoryLayout.STD430;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * Every parameter collection's values, a vec4 each, read by a material naming a {@code CG_GLOBAL_} parameter:
 * {@code CgParameterCollection}'s storage. Uploaded once a frame by the world renderer; nothing else writes it.
 *
 * <ul>
 *   <li>Render thread only. The class makes its buffer when first touched, after {@code CgEngineLifecycle.ensureGraphicsContext}.</li>
 * </ul>
 */
public final class CgParameterCollectionBuffer {

    /** The {@code attach()} macro the env file reads values through. */
    public static final String MACRO_NAME = "GLOBAL_DATA";
    /** Where its shaders read it, and where a draw names one of its own. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("globals");

    public static final CgBufferLayout FORMAT = CgBufferLayout.builder("CgGlobal", STD430).vec4("value").build();

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgGlobals", FORMAT, SLOT, CgBufferLifetime.FRAME);

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
