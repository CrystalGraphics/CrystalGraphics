package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.vfx.look.CgVfxParam;

/**
 * One emitter input bound to a user parameter (Niagara's "any input bound to any user parameter"): a word of the
 * definition's parameter row read from an instance lane instead, its value {@code scale * value + offset} worked out
 * on the CPU once a step per instance from the effect's values ({@link CgVfxInstanceView#user}).
 *
 * <pre>{@code
 * // fx_gravity's strength (module 0, word 0) follows the effect's GRAVITY, times 9.8
 * CgVfxEmitter.builder("sparks").module(new CgVfxModule.Gravity(9.8f))
 *         .bind(CgVfxUserBinding.module(0, 0, MyEffect.GRAVITY, 9.8f, 0f))...
 * // the launch's speed max (spawn word 1) follows SPEED: new spawns only
 *         .bind(CgVfxUserBinding.spawn(1, MyEffect.SPEED, 1f, 0f))
 * }</pre>
 *
 * <ul>
 *   <li>A bound module input reaches every live particle at the next step; a bound spawn input reaches new spawns only.</li>
 *   <li>Which words are bound is part of the shape ({@code CgVfxShape}): definitions binding different words step in
 *       different pools. The parameter's value never recompiles anything.</li>
 *   <li>{@code word} counts floats from the block's start: vector {@code word / 4}, component {@code word % 4}.</li>
 * </ul>
 *
 * @param module {@link #SPAWN} for the spawn numbers ({@link CgVfxGpuEmitter#writeSpawn}), else the module's index in the stack
 * @param word   the float within that block
 * @param param  the user parameter read
 * @param scale  its value's multiplier
 * @param offset added after
 */
public record CgVfxUserBinding(int module, int word, CgVfxParam.Scalar param, float scale, float offset) {

    /** {@link #module} for the spawn numbers. */
    public static final int SPAWN = -1;

    public CgVfxUserBinding {
        if (module < SPAWN) throw new IllegalArgumentException("module " + module);
        if (word < 0) throw new IllegalArgumentException("word " + word);
        if (param == null) throw new IllegalArgumentException("a binding needs a parameter");
    }

    /** Spawn word {@code word} bound to {@code param}. */
    public static CgVfxUserBinding spawn(int word, CgVfxParam.Scalar param, float scale, float offset) {
        if (word >= CgVfxGpuEmitter.SPAWN_VECTORS * 4) throw new IllegalArgumentException("spawn word " + word);
        return new CgVfxUserBinding(SPAWN, word, param, scale, offset);
    }

    /** Module {@code module}'s word {@code word} bound to {@code param}. */
    public static CgVfxUserBinding module(int module, int word, CgVfxParam.Scalar param, float scale, float offset) {
        return new CgVfxUserBinding(module, word, param, scale, offset);
    }

    /** Its lane value for a user parameter at {@code value}. */
    public float apply(float value) {
        return scale * value + offset;
    }
}
