package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;

import java.util.List;

/**
 * What a particle kind reads besides its particle: the world, the scene's depth, a fluid, its neighbours. A kind names
 * the interfaces it takes ({@link CgVfxGpuModule#dataInterfaces}), and its {@code fx_<kind>} function receives each as
 * an argument after its lanes, in that order. Niagara's data interfaces ({@code UNiagaraDataInterface}): the GLSL the
 * Step kernel includes, what each dispatch binds, and what it keeps per instance, per pool and per frame.
 * {@code CgVfxDataInterfaces} holds the engine's.
 *
 * <pre>{@code
 * // a kind taking the floor and the scene's depth
 * private static final CgVfxDataInterface[] READS = {CgVfxDataInterfaces.FLOOR_Y, CgVfxDataInterfaces.DEPTH};
 * public CgVfxDataInterface[] dataInterfaces() { return READS; }
 * // void fx_mine(inout FxParticle p, FxStep s, vec4 m, float floorY, FxDepth depth)
 * }</pre>
 *
 * A mod's own: its GLSL, what each dispatch binds, and its id under its namespace, registered once
 * ({@code CgVfxDataInterfaces.register}).
 *
 * <pre>{@code
 * public final class Magnet implements CgVfxDataInterface {
 *     public String id() { return "mymod:magnet"; }
 *     public String type() { return "vec4"; }
 *     public String call() { return "_Magnet"; }                       // the argument the kind receives
 *     public boolean beforeSolver() { return true; }                    // a force
 *     public String properties() { return "    _Magnet (\"Where it pulls, and how hard\", vec4) = (0, 0, 0, 0)\n"; }
 *     public void bind(CgDispatch d, CgVfxParticlePool pool) { d.set("_Magnet", x, y, z, strength); }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>An interface is part of a pool's identity by its {@link #id} alone: two kinds taking it share its GLSL, once.</li>
 *   <li>GLSL names are global to the kernel: prefix every property, buffer and function with the interface's own.</li>
 *   <li>A kind before the solver takes only interfaces that say {@link #beforeSolver}.</li>
 *   <li>{@link #call} is GLSL in the Step's per-particle loop: {@code p}, {@code inst} (the instance row's first vec4),
 *       {@code live} and {@code CG_ELEMENT} are in scope.</li>
 * </ul>
 */
public interface CgVfxDataInterface {

    /** Its name, {@code namespace:path}: what a pool's key and a registry know it by. */
    String id();

    /** The GLSL type a kind's function receives it as. */
    String type();

    /** The GLSL expression the Step passes a kind for it. */
    String call();

    /** Whether a kind before the solver may take it: a force from it, rather than a reaction to where the step went. */
    default boolean beforeSolver() {
        return false;
    }

    /** Interfaces whose GLSL and binds it needs too, included ahead of its own. */
    default List<CgVfxDataInterface> requires() {
        return List.of();
    }

    /** The GLSL libraries it includes, in order. */
    default List<String> libraries() {
        return List.of();
    }

    /** GLSL declared ahead of the kinds' code: prototypes of functions a kind calls, defined in {@link #functions}. */
    default String declarations() {
        return "";
    }

    /** Lines of the Step's {@code Properties} block. */
    default String properties() {
        return "";
    }

    /** GLSL structs its buffers hold, placed before the {@code Buffers} block. */
    default String structs() {
        return "";
    }

    /** Lines of the Step's {@code Buffers} block. */
    default String buffers() {
        return "";
    }

    /** Its GLSL functions; {@code lanesAt} is where its {@link #instanceLanes} start in the instance row. */
    default String functions(int lanesAt) {
        return "";
    }

    /** Vec4s of the instance row it keeps per instance, after every module's. */
    default int instanceLanes() {
        return 0;
    }

    /** Writes its {@link #instanceLanes} for an instance whose origin is {@code (ox, oy, oz)}, each step. */
    default void writeInstance(CgVfxInstanceView view, double ox, double oy, double oz, CgVfxWords words) {
    }

    /** Compiles its own kernels, with a shape's Step ({@code CgVfxParticlePool.prepare}), so no frame waits on them. */
    default void prepare() {
    }

    /** The context is gone: drops what it kept on the GPU, made again at its next use. */
    default void forget() {
    }

    /** Once a frame, at the particles' stage, when a pool stepping that frame takes it: gathers what it reads. */
    default void beforeFrame(CgStageFrame frame) {
    }

    /** Once a recording that steps a pool taking it, before any step: its shared uploads. */
    default void upload(CgRecording recording) {
    }

    /** Before a pool taking it steps in a recording: its per-pool storage, sized to the pool. */
    default void reserve(CgRecording recording, CgVfxParticlePool pool) {
    }

    /** After each step of a pool taking it: Niagara's post-stage passes. */
    default void afterStep(CgComputePass pass, CgVfxParticlePool pool) {
    }

    /** What a step's dispatch reads of it: its properties, buffers and textures. */
    void bind(CgDispatch dispatch, CgVfxParticlePool pool);
}
