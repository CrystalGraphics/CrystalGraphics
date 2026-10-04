package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.buffer.CgGpuType;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * The frame's particles for the GPU: one std430 record per particle in a FRAME-lifetime buffer, read by index through
 * {@code #pragma cg_use particle} ({@code PARTICLE_DATA(n)}, and the {@code CG_PARTICLE_*} macros in
 * {@code env/buffer/particle.glsl}). Whatever simulates particles writes every live one once a frame, before the world
 * stages record, and each draw reads its own range.
 *
 * <pre>{@code
 * CgParticleBuffer.begin(total);
 * for (...) CgParticleBuffer.put(x, y, z, size,  vx, vy, vz, progress,  seed, spin, heat, opacity,  light);
 * CgParticleBuffer.end();
 * // a draw reading records [base, base + count) passes base and count in its custom data
 * }</pre>
 *
 * <ul>
 *   <li>Write it every frame any particle draws, even when nothing moved: a FRAME buffer read in a frame that did not
 *       write it reads another frame's bytes, silently.</li>
 *   <li>Render thread only; {@link #begin} with the exact number of {@link #put}s that follow.</li>
 *   <li>Positions are whatever space the drawing shader expects; the vfx engine writes them relative to an effect's
 *       origin and passes the origin per draw.</li>
 * </ul>
 */
public final class CgParticleBuffer {

    /** The {@code attach()} macro shaders read records through. */
    public static final String MACRO_NAME = "PARTICLE_DATA";

    /**
     * place: position and size; motion: velocity and progress through life; state: seed, spin, heat, opacity; light: block
     * and sky light, 0 to 15, then two unused.
     */
    public static final CgBufferFormat FORMAT = CgBufferFormat.builder("CgParticle", STD430)
            .vec4("place")
            .vec4("motion")
            .vec4("state")
            .vec4("light")
            .build();

    private static final String NAME = "CgParticleBuffer";

    /** Allocates against {@link CgBindingPoints#PARTICLES}: only valid after {@code CgGraphicsLifecycle.initContext}. */
    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal(NAME, FORMAT, CgBindingPoints.PARTICLES, CgBufferLifetime.FRAME);
    private static final CgBufferWriter WRITER = BUFFER.writer();
    private static final int PLACE = WRITER.offsetOf("place", CgGpuType.VEC4);
    private static final int MOTION = WRITER.offsetOf("motion", CgGpuType.VEC4);
    private static final int STATE = WRITER.offsetOf("state", CgGpuType.VEC4);
    private static final int LIGHT = WRITER.offsetOf("light", CgGpuType.VEC4);

    private CgParticleBuffer() {
    }

    /**
     * The buffer, for {@code CgEngineBufferRegistry}, which seeds the {@code particle} token with a method reference
     * to this so registering never initialises this class.
     */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }

    /** Opens this frame's write of exactly {@code count} records. */
    public static void begin(int count) {
        BUFFER.beginWrite(count);
    }

    /** Writes the next record; {@code light} is {@code block | sky << 4}, as {@code CgWorldLight.at} answers. */
    public static void put(float x, float y, float z, float size, float vx, float vy, float vz, float progress,
                           float seed, float spin, float heat, float opacity, int light) {
        WRITER.beginRecord();
        WRITER.vec4At(PLACE, x, y, z, size);
        WRITER.vec4At(MOTION, vx, vy, vz, progress);
        WRITER.vec4At(STATE, seed, spin, heat, opacity);
        WRITER.vec4At(LIGHT, light & 0xF, light >> 4 & 0xF, 0f, 0f);
        BUFFER.endRecord();
    }

    /** Uploads this frame's records. */
    public static void end() {
        BUFFER.endWrite();
    }
}
