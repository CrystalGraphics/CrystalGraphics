package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferLayout.MemoryLayout.STD430;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * The order particles are drawn in: each drawn particle's record in its pool, culled and sorted by Range
 * ({@code CgVfxRange.order()}), read at {@link #SLOT}. The {@code particle} engine buffer brings it,
 * and the {@code CG_PARTICLE_*} macros read through it.
 *
 * <pre>{@code
 * world.draw(quads, spark).buffer(CgParticleBuffer.SLOT, pool.drawn())
 *      .buffer(SLOT, range.order())...
 * }</pre>
 *
 * This class is the declaration: nothing writes its own buffer.
 */
public final class CgParticleOrderBuffer {

    /** The {@code attach()} macro the particle macros read the order through. */
    public static final String MACRO_NAME = "PARTICLE_ORDER_DATA";
    /** Where its shaders read it, and where a draw names one of its own. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("particle_order");

    public static final CgBufferLayout FORMAT = CgBufferLayout.builder("CgParticleOrder", STD430).float_("index").build();

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgParticleOrderBuffer", FORMAT, SLOT, CgBufferLifetime.FRAME);

    private CgParticleOrderBuffer() {
    }

    /** The declaration's buffer, for {@code CgEngineBufferRegistry}. */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }
}
