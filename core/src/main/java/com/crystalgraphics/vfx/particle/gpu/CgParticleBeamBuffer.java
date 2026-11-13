package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferLayout.MemoryLayout.STD430;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * A beam draw's noise points: its pool's beam runs ({@code CgVfxParticlePool.beamRuns()}), bound at
 * {@link #SLOT}. The {@code particle} engine buffer brings it, so a ported material draws a beam
 * as Unreal's beam-and-trail vertex factory places one; {@code CG_PARTICLE_BEAM(i)} reads vec4 {@code i}.
 *
 * <pre>{@code
 * world.draw(strip, material).buffer(CgParticleBuffer.SLOT, pool.drawn())
 *      .buffer(CgParticleOrderBuffer.SLOT, range.order()).buffer(SLOT, pool.beamRuns())...
 * }</pre>
 *
 * This class is the declaration: nothing writes its own buffer.
 */
public final class CgParticleBeamBuffer {

    /** The {@code attach()} macro the beam factory reads the runs through. */
    public static final String MACRO_NAME = "PARTICLE_BEAM_DATA";
    /** Where its shaders read it, and where a draw names one of its own. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("particle_beams");

    public static final CgBufferLayout FORMAT = CgBufferLayout.builder("CgParticleBeam", STD430).vec4("value").build();

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgParticleBeamBuffer", FORMAT, SLOT, CgBufferLifetime.FRAME);

    private CgParticleBeamBuffer() {
    }

    /** The declaration's buffer, for {@code CgEngineBufferRegistry}. */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }
}
