package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.object.value.CgUnits;

/**
 * How a {@link CgVfxModule.VectorField}'s grid is placed and turned, Cascade's local vector field
 * ({@code ParticleGpuSimulation.cpp:3213-3225, 3813, 4176}): its grid's origin sits {@code pivot} short of the box's
 * centre, and the grid turns about that origin by its rotator, a start rolled once per instance between two ends plus a
 * rate times the instance's time, then by its placement.
 *
 * <pre>{@code
 * // a field spun a quarter turn a second about Unreal's yaw, placed unturned
 * float q = (float) (Math.PI / 2);
 * new CgVfxFieldTurn(new float[3], new float[]{0f, 0f, 0f, 1f}, new float[3], new float[3], new float[]{0f, 0f, q});
 * }</pre>
 *
 * <ul>
 *   <li>Rotators are Unreal's (roll, pitch, yaw) in radians, summed as Cascade sums its {@code FRotator}, and turned
 *       through Unreal's axes ({@link CgUnits#rotatorToHost}).</li>
 *   <li>One roll mixes all three axes, as Cascade's {@code LerpRange} does.</li>
 * </ul>
 *
 * @param pivot  the grid's box centre from its origin, in blocks
 * @param place  its placement, a host quaternion
 * @param from   its start's low end
 * @param to     its start's high end
 * @param rate   radians a second
 */
public record CgVfxFieldTurn(float[] pivot, float[] place, float[] from, float[] to, float[] rate) {

    /** Unturned, its box centred on its origin. */
    public static final CgVfxFieldTurn NONE = new CgVfxFieldTurn(new float[3], new float[]{0f, 0f, 0f, 1f}, new float[3],
            new float[3], new float[3]);

    /** Its turn {@code seconds} into an instance whose start rolled {@code u}, as a host quaternion into {@code out}. */
    public void at(float u, float seconds, float[] out) {
        CgUnits.rotatorToHost(from[0] + (to[0] - from[0]) * u + rate[0] * seconds, from[1] + (to[1] - from[1]) * u + rate[1] * seconds,
                from[2] + (to[2] - from[2]) * u + rate[2] * seconds, out);
        float x = out[0], y = out[1], z = out[2], w = out[3];
        float px = place[0], py = place[1], pz = place[2], pw = place[3];
        // The grid's own turn first, then its placement: place times it.
        out[0] = pw * x + px * w + py * z - pz * y;
        out[1] = pw * y - px * z + py * w + pz * x;
        out[2] = pw * z + px * y - py * x + pz * w;
        out[3] = pw * w - px * x - py * y - pz * z;
    }
}
