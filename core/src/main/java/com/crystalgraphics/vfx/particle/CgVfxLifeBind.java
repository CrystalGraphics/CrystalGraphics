package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;

import java.util.Map;

/**
 * A look over life bound to a user parameter: Cascade's {@code ParticleParameter} distribution in a size or colour
 * module, its instance's parameter remapped ({@code ParticleModules_Parameter.cpp}), the same for every particle of the
 * instance and read again each step. Multiplies what its target draws.
 *
 * <pre>{@code
 * // scale_mesh_size's scale: { bind: user.BeamLength, default: [100, 1, 1] }
 * b.lifeBind(CgVfxLifeBind.of(CgVfxLifeBind.Target.MESH_SCALE, binding, source));
 * }</pre>
 *
 * <ul>
 *   <li>Binds to one target multiply together.</li>
 *   <li>Only {@code user.*} parameters: a particle's own attribute is a form of the Step's.</li>
 * </ul>
 *
 * @param target    what it multiplies
 * @param bind      the value: its parameter, remap, mode and default
 * @param parameter its user parameter's name, without {@code user.}
 * @param source    its form as its file gave it, which a file writes back
 */
public record CgVfxLifeBind(Target target, CgVfxFormInput.Binding bind, String parameter, Map<?, ?> source) {

    /** What a bind multiplies, and its lanes in the instance's two: mesh scale xyz, sprite size, colour rgb, alpha. */
    public enum Target {
        MESH_SCALE(0, 3), SPRITE_SIZE(3, 1), COLOR(4, 3), ALPHA(7, 1);

        /** Its first lane, and how many. */
        public final int lane, lanes;

        Target(int lane, int lanes) {
            this.lane = lane;
            this.lanes = lanes;
        }
    }

    /** Lanes every emitter's binds take in its instance row: two vec4s. */
    public static final int LANES = 8;

    /** {@code bind} on {@code target}; its path must be a user parameter's. */
    public static CgVfxLifeBind of(Target target, CgVfxFormInput.Binding bind, Map<?, ?> source) {
        if (!bind.path().startsWith("user.")) {
            throw new IllegalArgumentException("a look over life binds a user parameter, not " + bind.path());
        }
        return new CgVfxLifeBind(target, bind, bind.path().substring("user.".length()), source);
    }
}
