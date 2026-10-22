package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * A beam draw's noise points: its pool's beam runs ({@code CgVfxParticlePool.beamRuns()}), bound at
 * {@link CgBindingPoints#PARTICLE_BEAMS}. {@code #pragma cg_use particle} brings it, so a ported material draws a beam
 * as Unreal's beam-and-trail vertex factory places one; {@code CG_PARTICLE_BEAM(i)} reads vec4 {@code i}.
 *
 * <pre>{@code
 * world.draw(strip, material).buffer(CgBindingPoints.PARTICLES, pool.drawn())
 *      .buffer(CgBindingPoints.PARTICLE_ORDER, range.order()).buffer(CgBindingPoints.PARTICLE_BEAMS, pool.beamRuns())...
 * }</pre>
 *
 * This class is the declaration: nothing writes its own buffer.
 */
public final class CgParticleBeamBuffer {

    /** The {@code attach()} macro the beam factory reads the runs through. */
    public static final String MACRO_NAME = "PARTICLE_BEAM_DATA";

    public static final CgBufferFormat FORMAT = CgBufferFormat.builder("CgParticleBeam", STD430).vec4("value").build();

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgParticleBeamBuffer", FORMAT, CgBindingPoints.PARTICLE_BEAMS, CgBufferLifetime.FRAME);

    private CgParticleBeamBuffer() {
    }

    /** The declaration's buffer, for {@code CgEngineBufferRegistry}. */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }
}
