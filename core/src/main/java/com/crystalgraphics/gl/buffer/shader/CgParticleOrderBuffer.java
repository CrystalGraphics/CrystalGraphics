package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * The order particles are drawn in: each drawn particle's record in its pool, culled and sorted by Range
 * ({@code CgVfxRange.order()}), bound at {@link CgBindingPoints#PARTICLE_ORDER}. {@code #pragma cg_use particle} brings it,
 * and the {@code CG_PARTICLE_*} macros read through it.
 *
 * <pre>{@code
 * world.draw(quads, spark).buffer(CgBindingPoints.PARTICLES, pool.records())
 *      .buffer(CgBindingPoints.PARTICLE_ORDER, range.order())...
 * }</pre>
 *
 * This class is the declaration: nothing writes its own buffer.
 */
public final class CgParticleOrderBuffer {

    /** The {@code attach()} macro the particle macros read the order through. */
    public static final String MACRO_NAME = "PARTICLE_ORDER_DATA";

    public static final CgBufferFormat FORMAT = CgBufferFormat.builder("CgParticleOrder", STD430).float_("index").build();

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgParticleOrderBuffer", FORMAT, CgBindingPoints.PARTICLE_ORDER, CgBufferLifetime.FRAME);

    private CgParticleOrderBuffer() {
    }

    /** The declaration's buffer, for {@code CgEngineBufferRegistry}. */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }
}
