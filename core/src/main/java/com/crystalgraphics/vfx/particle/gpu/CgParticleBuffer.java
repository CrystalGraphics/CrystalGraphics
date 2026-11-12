package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferLayout.MemoryLayout.STD430;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * The particle record a shader reads through {@code #pragma cg_use particle} (the {@code CG_PARTICLE_*} macros in
 * {@code env/buffer/particle.glsl}): a GPU pool's drawn part, written by the step's last stage into the pool's own
 * buffer at each record's index, read at {@link #SLOT}. A draw's particle {@code n} is the record
 * {@code CgParticleOrderBuffer} names at {@code n}: Range's culled, sorted order, as Niagara's GPU renderers read the
 * simulation's buffer through its sorted indices.
 *
 * <pre>{@code
 * world.draw(quads, material).buffer(SLOT, pool.drawn())
 *      .buffer(CgParticleOrderBuffer.SLOT, range.order())...   // particles [base, base + count) of the order
 * }</pre>
 *
 * This class is the declaration: nothing writes its own buffer.
 */
public final class CgParticleBuffer {

    /** The {@code attach()} macro shaders read records through. */
    public static final String MACRO_NAME = "PARTICLE_DATA";
    /** Where its shaders read it, and where a draw names one of its own. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("particle");

    /**
     * place: position and size; motion: velocity and progress through life; state: seed, spin, heat, opacity; light: block
     * and sky light, 0 to 15, as block + 16 sky, its spawn index (low 24 bits), then the floor under it as its height over
     * it and 1 (for kinds that land on a floor); color: rgb multiplying a look's colours, then the opacity again; scale:
     * each axis's scale of its size, then its age in seconds.
     */
    public static final CgBufferLayout FORMAT = CgBufferLayout.builder("CgParticle", STD430)
            .vec4("place")
            .vec4("motion")
            .vec4("state")
            .vec4("light")
            .vec4("color")
            .vec4("scale")
            .build();

    /** Floats a record takes. */
    public static final int RECORD_FLOATS = FORMAT.getFloatCount();

    private static final String NAME = "CgParticleBuffer";

    /** Allocated at {@link #SLOT}: only valid after {@code CgEngineLifecycle.ensureGraphicsContext}. */
    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal(NAME, FORMAT, SLOT, CgBufferLifetime.FRAME);

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
