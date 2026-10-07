package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.api.texture.CgTexture;

/**
 * A module kind's GPU side: the GLSL function the emitter compiler calls in its Step kernel, in stack order, and the
 * numbers that call gets. A kind is one function, {@code fx_<kind>} in
 * {@code crystalgraphics:shaders/lib/vfx/sim/fx_<kind>.glsl}, which includes {@code fx_types.glsl} for its structs.
 * Since the kind declares what it takes, a new kind is that file and these methods, never a compiler change.
 *
 * <pre>{@code
 * record Gravity(float strength) implements CgVfxModule {          // CgVfxModule extends CgVfxGpuModule
 *     public String gpuKind() { return "gravity"; }
 *     public void writeParams(CgVfxWords out) { out.vec4(strength, 0f, 0f, 0f); }
 * }
 * // fx_gravity.glsl
 * void fx_gravity(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) { f.accel.y -= m.x; }
 * }</pre>
 *
 * <p>Values the CPU works out per instance each step, in doubles where the world's coordinates need them:</p>
 * <pre>{@code
 * private static final CgVfxLane[] LANES = {CgVfxLane.IVEC4, CgVfxLane.VEC4};
 * public CgVfxLane[] instanceLanes() { return LANES; }
 * public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) { out.ivec4(...).vec4(...); }
 * // void fx_turbulence(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, ivec4 cell, vec4 frac)
 * }</pre>
 *
 * <p>After the solver: no forces, and the world values it declares after its lanes:</p>
 * <pre>{@code
 * private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};
 * public boolean afterSolve() { return true; }
 * public CgVfxWorldInput[] worldInputs() { return WORLD; }
 * // void fx_ground(inout FxParticle p, FxStep s, vec4 m, float floorY)
 * }</pre>
 *
 * <p>A kind of the caller's own, its GLSL given as text rather than a file (Unity VFX Graph's Custom HLSL block, Niagara's
 * scratch pad):</p>
 * <pre>{@code
 * public String gpuKind() { return "mymod_swirl"; }
 * public String gpuSource() {
 *     return """
 *             void fx_mymod_swirl(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
 *                 f.accel += cross(vec3(0.0, 1.0, 0.0), p.position) * m.x;
 *             }
 *             """;
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #gpuKind()} names the file and the function: lower case letters, digits and underscores.</li>
 *   <li>Write exactly what is declared: {@link #paramVectors()} vec4s in {@link #writeParams}, one per lane in
 *       {@link #writeInstance}; the pool throws otherwise, naming the kind.</li>
 *   <li>Answer the arrays as constants: they are asked for every step.</li>
 *   <li>{@link #writeParams} holds the definition's numbers, shared by every instance of it; anything that differs per
 *       instance is a lane. Numbers never recompile anything: the shape holds the kind and its counts only.</li>
 *   <li>World inputs are for a kind after the solver; one before it declaring any is refused.</li>
 * </ul>
 */
public interface CgVfxGpuModule {

    /** {@code fx_<kind>}'s {@code <kind>}: its function and its file. */
    String gpuKind();

    /**
     * The kind's GLSL, in place of {@code fx_<kind>.glsl}; null for the file. It sees {@code fx_types.glsl}, and
     * {@code fx_world_at.glsl} when the kind takes the world. One text a kind name: two kinds giving different text under
     * one name throw when the second is compiled. Prefix the name with the mod's, as for any resource.
     */
    default String gpuSource() {
        return null;
    }

    /** True for a kind that runs after the solver has moved the particles. */
    boolean afterSolve();

    /** How many vec4s of numbers it takes, as {@code m} or {@code m0, m1, ...}. */
    default int paramVectors() {
        return 1;
    }

    /** Its per-instance values, in the order its function takes them after its numbers. */
    default CgVfxLane[] instanceLanes() {
        return CgVfxLane.NONE;
    }

    /** The world values it takes after its lanes; only after the solver. */
    default CgVfxWorldInput[] worldInputs() {
        return CgVfxWorldInput.NONE;
    }

    /**
     * Textures it samples, passed after its world inputs as {@code sampler2D} or {@code sampler3D} by each one's kind:
     * a vector field (Unity's Vector Field Force, Niagara's Sample Vector Field), a heightfield (Godot's). Each texture
     * is part of the pool's identity, so definitions sampling different ones step in different pools; numbers placing it
     * stay in {@link #writeParams} and the lanes.
     *
     * <pre>{@code
     * private final CgTexture[] field = {CgTexture3D.create(CgTextureType.RGBA32F.toTextureSpec(), "mymod:fields/swirl.png")};
     * public CgTexture[] textures() { return field; }
     * // void fx_mymod_field(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, sampler3D field)
     * //     { f.accel += texture(field, (p.position - m.xyz) * m.w).xyz; }
     * }</pre>
     *
     * <ul>
     *   <li>Answer the same array every time: it is asked as definitions open and as each step binds.</li>
     *   <li>A kind name keeps one signature: two definitions giving it a 2D and a 3D texture break the second's shape.</li>
     * </ul>
     */
    default CgTexture[] textures() {
        return NO_TEXTURES;
    }

    /** No textures. Never write into it. */
    CgTexture[] NO_TEXTURES = {};

    /** Writes its {@link #paramVectors()} vec4s of numbers: once, when its definition first plays in a pool. */
    void writeParams(CgVfxWords out);

    /** Writes one vec4 per lane for {@code instance}, at a step's start; nothing for a kind with no lanes. */
    default void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
    }
}
