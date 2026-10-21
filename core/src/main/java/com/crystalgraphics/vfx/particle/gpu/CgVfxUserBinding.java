package com.crystalgraphics.vfx.particle.gpu;

import javax.annotation.Nullable;

import com.crystalgraphics.vfx.look.CgVfxParam;

/**
 * One emitter input bound to a user parameter (Niagara's "any input bound to any user parameter"): a word of the
 * definition's parameter row read from an instance lane instead, its value {@code scale * value + offset} worked out
 * on the CPU once a step per instance from the effect's values ({@link CgVfxInstanceView#user}). A module's input is
 * bound by its declared name, which the shape resolves to its word.
 *
 * <pre>{@code
 * // fx_gravity's pull (module 0's "gravity", its y) follows the effect's GRAVITY, times -9.8
 * CgVfxEmitter.builder("sparks").module(new CgVfxModule.Gravity(9.8f))
 *         .bind(CgVfxUserBinding.module(0, "gravity", 1, MyEffect.GRAVITY, -9.8f, 0f))...
 * // a scalar input: its only component
 *         .bind(CgVfxUserBinding.module(1, "drag", MyEffect.DRAG, 1f, 0f))
 * // the launch's speed max (spawn word 1) follows SPEED: new spawns only
 *         .bind(CgVfxUserBinding.spawn(1, MyEffect.SPEED, 1f, 0f))
 * }</pre>
 *
 * <ul>
 *   <li>A bound module input reaches every live particle at the next step; a bound spawn input reaches new spawns only.</li>
 *   <li>Which words are bound is part of the shape ({@code CgVfxShape}): definitions binding different words step in
 *       different pools. The parameter's value never recompiles anything.</li>
 *   <li>An input the module's kind does not declare in its words throws as the shape is made, naming both.</li>
 * </ul>
 *
 * @param module {@link #SPAWN} for the spawn numbers ({@link CgVfxGpuEmitter#writeSpawn}), else the module's index in the stack
 * @param input  the module's input by its declared name; null for the spawn numbers
 * @param index  the input's component; for the spawn numbers, the float from their start
 * @param param  the user parameter read
 * @param scale  its value's multiplier
 * @param offset added after
 */
public record CgVfxUserBinding(int module, @Nullable String input, int index, CgVfxParam.Scalar param, float scale,
                               float offset) {

    /** {@link #module} for the spawn numbers. */
    public static final int SPAWN = -1;

    public CgVfxUserBinding {
        if (module < SPAWN) throw new IllegalArgumentException("module " + module);
        if (index < 0) throw new IllegalArgumentException("index " + index);
        if ((module == SPAWN) != (input == null)) throw new IllegalArgumentException("a module's input is named; the spawn numbers' never");
        if (param == null) throw new IllegalArgumentException("a binding needs a parameter");
    }

    /** Spawn word {@code word} bound to {@code param}. */
    public static CgVfxUserBinding spawn(int word, CgVfxParam.Scalar param, float scale, float offset) {
        if (word >= CgVfxGpuEmitter.SPAWN_VECTORS * 4) throw new IllegalArgumentException("spawn word " + word);
        return new CgVfxUserBinding(SPAWN, null, word, param, scale, offset);
    }

    /** Component {@code component} of module {@code module}'s input {@code input} bound to {@code param}. */
    public static CgVfxUserBinding module(int module, String input, int component, CgVfxParam.Scalar param, float scale,
                                          float offset) {
        return new CgVfxUserBinding(module, input, component, param, scale, offset);
    }

    /** Module {@code module}'s scalar input {@code input} bound to {@code param}. */
    public static CgVfxUserBinding module(int module, String input, CgVfxParam.Scalar param, float scale, float offset) {
        return module(module, input, 0, param, scale, offset);
    }

    /** Its lane value for a user parameter at {@code value}. */
    public float apply(float value) {
        return scale * value + offset;
    }
}
