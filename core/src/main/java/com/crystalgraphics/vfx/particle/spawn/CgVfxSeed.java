package com.crystalgraphics.vfx.particle.spawn;

import java.util.List;
import java.util.Map;

import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

/**
 * A seeded module's seed: which {@link CgVfxRandomStream} its inputs draw from. Mirrors Cascade's
 * {@code FParticleRandomSeedInfo} and {@code PrepRandomSeedInstancePayload} (4.21 {@code ParticleModules.cpp:665-712}):
 * one of {@code values} each instance, picked again each loop where {@code resetOnLoop}.
 *
 * <pre>{@code
 * CgVfxSeed seed = new CgVfxSeed(new int[]{14, 15, 21}, "IN_Beam", false, false, true, true);
 * int s = seed.pick(instanceSeedBits, loop, key);   // one of 14, 15, 21; the same on every client
 * }</pre>
 *
 * <ul>
 *   <li>An instance parameter ({@code fromInstance}) is never bound by an engine instance: as in Cascade with the parameter
 *       unset, the seed comes from {@code values}.</li>
 *   <li>No values gives seed 0, Cascade's zeroed stream.</li>
 * </ul>
 *
 * @param values          its seeds
 * @param parameter       the instance parameter Cascade reads it from, or null
 * @param fromInstance    whether Cascade reads {@code parameter} first
 * @param instanceIsIndex whether that parameter indexes {@code values}
 * @param resetOnLoop     whether each loop starts the stream again, a seed picked anew
 * @param randomSelection whether an instance picks among {@code values} rather than taking the first
 */
public record CgVfxSeed(int[] values, String parameter, boolean fromInstance, boolean instanceIsIndex, boolean resetOnLoop,
                        boolean randomSelection) {

    /** The input a value's seed names to draw after, in its stream ({@code { follows: color }}); null for its own stream. */
    public static String follows(Object given) {
        return given instanceof Map<?, ?> m && m.get("follows") instanceof String f ? f : null;
    }

    /** A file's {@code seed} struct, Unreal's defaults for what it leaves out. */
    public static CgVfxSeed of(String name, Object given) {
        if (!(given instanceof Map<?, ?> m)) throw new IllegalArgumentException(name + ": seed " + given + " is not a struct");
        int[] values;
        if (m.get("values") == null) {
            values = new int[0];
        } else if (m.get("values") instanceof List<?> list) {
            values = new int[list.size()];
            for (int i = 0; i < values.length; i++) {
                if (!(list.get(i) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                    throw new IllegalArgumentException(name + ": seed value " + list.get(i) + " is not a whole number");
                }
                values[i] = n.intValue();
            }
        } else {
            throw new IllegalArgumentException(name + ": seed values " + m.get("values") + " is not a list");
        }
        return new CgVfxSeed(values, m.get("parameter") instanceof String p ? p : null, Boolean.TRUE.equals(m.get("from_instance")),
                Boolean.TRUE.equals(m.get("instance_is_index")), !Boolean.FALSE.equals(m.get("reset_on_loop")),
                Boolean.TRUE.equals(m.get("random_selection")));
    }

    /**
     * The seed an instance seeded {@code instanceSeedBits} starts loop {@code loop} with ({@code loop} 0 unless
     * {@link #resetOnLoop}); {@code key} tells two modules' picks apart.
     */
    public int pick(int instanceSeedBits, int loop, int key) {
        if (values.length == 0) return 0;
        if (!randomSelection || values.length == 1) return values[0];
        float u = CgVfxEmitterInstance.rand(instanceSeedBits ^ key, loop, 0);
        return values[Math.min(values.length - 1, (int) (u * values.length))];
    }
}
