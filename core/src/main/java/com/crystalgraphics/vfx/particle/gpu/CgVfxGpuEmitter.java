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
 *     out.vec4(speedMin, speedMax, lifeMin, lifeMax)
 *        .vec4(sizeMin, sizeMax, sizeSkew, heat)
 *        .vec4(spinMin, spinMax, inherit, 0f)
 *        .vec4(frontA, frontB, 0f, 0f);
 *     spawnShape.write(out, upMin, upMax, upBias);   // six more: where it is born, which way it flies
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
    int SPAWN_VECTORS = 10;

    /** For messages. */
    String name();

    CgVfxEmitter.Renderer renderer();

    /** Its module stack, in the order each step runs it. */
    List<? extends CgVfxGpuModule> modules();

    /**
     * Writes its spawn numbers, {@link #SPAWN_VECTORS} vec4s in this order: (speed min, speed max, life min, life max),
     * (size min, size max, size skew, heat), (spin min, spin max, the share of its source's velocity, 0), (front a, front
     * b, 0, 0): spawn k starts {@code a k - b k^2} blocks further out, its k held where that peaks ({@code a / 2b}), 0, 0
     * for a shape that stays put; then where it is born: (up min, up max, up bias, direction mode), (direction xyz, cos
     * of its spread), (shape kind, placement | 4 for the upper half, arc, ordered), (the shape's numbers), its rotation
     * from the sampling frame (axis +Y), (its offset from the source, 0). {@code CgVfxSpawnShape} has the codes; a mesh's
     * numbers name its section of {@code CgVfxSpawnMeshes}.
     */
    void writeSpawn(CgVfxWords out);

    /** Whether it spawns on a mesh: its shape's Step kernel then reads {@code CgVfxSpawnMeshes}. */
    default boolean spawnsOnMesh() {
        return false;
    }

    /**
     * How many of each particle's last positions its pool keeps, one a step, for a {@code trails()} layer to draw: 0
     * keeps none.
     */
    default int trailPoints() {
        return 0;
    }

    /**
     * The density each particle holds at its middle in the four channels of its instance's grid, drawn there each frame
     * where the particle is drawn; null for none. The array itself, read every frame: never written.
     */
    default float[] rasterized() {
        return null;
    }

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

    /** Samples each of a curve row's two curves holds. */
    int CURVE_TEXELS = 256;

    /** The vec4s a curve row holds after its two curves: the two start colours, then the two start scales. */
    int CURVE_STARTS = 4;

    /**
     * Writes its curve row from {@code out[at]}, vec4s: {@code texels} shape samples, {@code texels} tint samples, then
     * {@link #CURVE_STARTS} start values. Sample i is at progress {@code i / (texels - 1)} of its {@link #curveDomain()}.
     * The pool calls it when its parameter row opens.
     *
     * <ul>
     *   <li>a shape sample is each axis's scale, then the size multiplier;</li>
     *   <li>a tint sample is the colour multiplier's red, green and blue (times its intensity), then the opacity;</li>
     *   <li>the starts are the two colours a particle's start colour is mixed between (rgb times intensity, alpha), then
     *   the two scales its start axis scales are mixed between (xyz, 0).</li>
     * </ul>
     *
     * <pre>{@code
     * float[] row = new float[4 * (2 * CgVfxGpuEmitter.CURVE_TEXELS + CgVfxGpuEmitter.CURVE_STARTS)];
     * EMBERS.writeCurves(row, 0, CgVfxGpuEmitter.CURVE_TEXELS);
     * float sizeAtBirth = row[3], opacityAtBirth = row[4 * CgVfxGpuEmitter.CURVE_TEXELS + 3];
     * }</pre>
     */
    void writeCurves(float[] out, int at, int texels);

    /**
     * What its curves are sampled by: over life, or by speed.
     *
     * <pre>{@code
     * public CgVfxCurveDomain curveDomain() { return CgVfxCurveDomain.speed(0.5f, 6f); }   // sparks shrink as they slow
     * }</pre>
     */
    default CgVfxCurveDomain curveDomain() {
        return CgVfxCurveDomain.LIFE;
    }
}
