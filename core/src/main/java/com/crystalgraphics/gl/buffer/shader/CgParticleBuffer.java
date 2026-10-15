package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * The particle record a shader reads through {@code #pragma cg_use particle} ({@code PARTICLE_DATA(n)}, and the
 * {@code CG_PARTICLE_*} macros in {@code env/buffer/particle.glsl}): one std430 record per drawn particle, which the GPU
 * simulation's Place kernel writes ({@code CgVfxRange}) and each particle draw binds at {@link CgBindingPoints#PARTICLES}.
 *
 * <pre>{@code
 * world.draw(quads, material).buffer(CgBindingPoints.PARTICLES, range.drawn())...   // records [base, base + count)
 * }</pre>
 *
 * This class is the declaration: nothing writes its own buffer.
 */
public final class CgParticleBuffer {

    /** The {@code attach()} macro shaders read records through. */
    public static final String MACRO_NAME = "PARTICLE_DATA";

    /**
     * place: position and size; motion: velocity and progress through life; state: seed, spin, heat, opacity; light: block
     * and sky light, 0 to 15, as block + 16 sky, its spawn index (low 24 bits), then the floor under it as its height over
     * it and 1 (for kinds that land on a floor).
     */
    public static final CgBufferFormat FORMAT = CgBufferFormat.builder("CgParticle", STD430)
            .vec4("place")
            .vec4("motion")
            .vec4("state")
            .vec4("light")
            .build();

    /** Floats a record takes. */
    public static final int RECORD_FLOATS = FORMAT.getFloatCount();

    private static final String NAME = "CgParticleBuffer";

    /** Allocates against {@link CgBindingPoints#PARTICLES}: only valid after {@code CgGraphicsLifecycle.initContext}. */
    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal(NAME, FORMAT, CgBindingPoints.PARTICLES, CgBufferLifetime.FRAME);

    private CgParticleBuffer() {
    }

    /**
     * The declaration's buffer, for {@code CgEngineBufferRegistry}, which seeds the {@code particle} token with a method
     * reference to this so registering never initialises this class.
     */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }
}
