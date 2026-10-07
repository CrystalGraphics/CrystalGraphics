package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;

import java.util.List;

/**
 * An emitter definition as a GPU pool reads it: its module stack, its renderer and its spawn numbers.
 * {@code CgVfxEmitter} implements it; its {@code name()}, {@code renderer()} and {@code modules()} already answer three
 * of the four, once {@code CgVfxModule} extends {@link CgVfxGpuModule}.
 *
 * <pre>{@code
 * public void writeSpawn(CgVfxWords out) {
 *     out.vec4(shapeRadius, upMin, upMax, upBias)
 *        .vec4(speedMin, speedMax, lifeMin, lifeMax)
 *        .vec4(sizeMin, sizeMax, sizeSkew, heat)
 *        .vec4(spinMin, spinMax, 0f, 0f);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Two definitions with the same module kinds in the same order and the same renderer share a pool and its
 *       kernels ({@link CgVfxShape}); their numbers are rows of its parameter table.</li>
 *   <li>A definition is held by identity: a look that changes a number holds another definition, so another row.</li>
 * </ul>
 */
public interface CgVfxGpuEmitter {

    /** The vec4s {@link #writeSpawn} writes. */
    int SPAWN_VECTORS = 4;

    /** For messages. */
    String name();

    CgVfxEmitter.Renderer renderer();

    /** Its module stack, in the order the CPU runs it. */
    List<? extends CgVfxGpuModule> modules();

    /**
     * Writes its spawn numbers, {@link #SPAWN_VECTORS} vec4s in this order: (shape radius, up min, up max, up bias),
     * (speed min, speed max, life min, life max), (size min, size max, size skew, heat), (spin min, spin max, 0, 0).
     */
    void writeSpawn(CgVfxWords out);

    /**
     * What its particles do that it reacts to, in order: an event's index here is what {@link CgVfxEvent#childKey} and
     * {@link CgVfxEventListener} name it by. The triggers' kinds join its {@link CgVfxShape}; at most
     * {@link CgVfxEvent#MAX_EVENTS}.
     *
     * <pre>{@code
     * public List<CgVfxEvent> events() {
     *     return List.of(CgVfxEvent.onLanding().spawn(DUST, 3).inherit(0.2f), CgVfxEvent.onDeath().readback(16));
     * }
     * }</pre>
     */
    default List<CgVfxEvent> events() {
        return List.of();
    }

    /** Samples a curve row holds: the atlas's width. */
    int CURVE_TEXELS = 256;

    /**
     * Writes its curve row, {@code texels} pairs from {@code out[at]}: sample i, at progress {@code i / (texels - 1)}, is
     * its size multiplier then its opacity, what the CPU path applies at that point of a particle's life. The pool calls
     * it when its parameter row opens; the row is one RG32F row of the curve atlas.
     *
     * <pre>{@code
     * float[] row = new float[2 * CgVfxGpuEmitter.CURVE_TEXELS];
     * EMBERS.writeCurves(row, 0, CgVfxGpuEmitter.CURVE_TEXELS);   // row[0] size at birth, row[1] opacity at birth
     * }</pre>
     */
    void writeCurves(float[] out, int at, int texels);
}
