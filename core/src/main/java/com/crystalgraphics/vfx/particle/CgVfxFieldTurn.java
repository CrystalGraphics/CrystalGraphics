package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.object.value.CgUnits;

/**
 * How a {@link CgVfxModule.VectorField}'s grid is placed, stretched and turned: Cascade's local vector field
 * ({@code ParticleGpuSimulation.cpp:3326-3331}, {@code VectorField.cpp:87-96}). A point of the grid's own space goes to
 * the world as {@code instance turn · (translation + place · stretch · roll · v)}: its roll first, a start rolled once
 * per instance between two ends plus a rate times the instance's time, then the stretch, the placement, and the turn of
 * the effect it plays in (the point it follows), so the field rides a hand as the hand turns.
 *
 * <pre>{@code
 * // a field spun a quarter turn a second about Unreal's yaw, stretched three times up, placed unturned
 * float q = (float) (Math.PI / 2);
 * new CgVfxFieldTurn(new float[3], new float[]{0f, 0f, 0f, 1f}, new float[3], new float[3], new float[]{0f, 0f, q},
 *         new float[]{1f, 3f, 1f});
 * }</pre>
 *
 * <ul>
 *   <li>Rotators are Unreal's (roll, pitch, yaw) in radians, summed as Cascade sums its {@code FRotator}, and turned
 *       through Unreal's axes ({@link CgUnits#rotatorToHost}).</li>
 *   <li>One roll mixes all three axes, as Cascade's {@code LerpRange} does.</li>
 *   <li>The stretch scales the grid's box, never its vectors: they turn by the frame with its scale taken out
 *       ({@code VolumeToWorldNoScale}).</li>
 * </ul>
 *
 * @param pivot  the grid's box centre from its origin, in the grid's own space, in blocks
 * @param place  its placement, a host quaternion
 * @param from   its start's low end
 * @param to     its start's high end
 * @param rate   radians a second
 * @param scale  its stretch on each host axis: Cascade's {@code RelativeScale3D}
 */
public record CgVfxFieldTurn(float[] pivot, float[] place, float[] from, float[] to, float[] rate, float[] scale) {

    /** Unturned and unstretched, its box centred on its origin. */
    public static final CgVfxFieldTurn NONE = new CgVfxFieldTurn(new float[3], new float[]{0f, 0f, 0f, 1f}, new float[3],
            new float[3], new float[3], new float[]{1f, 1f, 1f});

    /**
     * Its frame {@code seconds} into an instance whose start rolled {@code u}, turned as the instance is by the host
     * quaternion {@code (qx, qy, qz, qw)}, into {@code out}: three rows taking a world offset into the grid's space,
     * then three rows turning the grid's vectors into the world.
     */
    public void frame(float u, float seconds, float qx, float qy, float qz, float qw, float[] out) {
        Scratch s = SCRATCH.get();
        float[] roll = s.roll, m = s.m, r = s.r, a = s.a, t = s.t;
        CgUnits.rotatorToHost(from[0] + (to[0] - from[0]) * u + rate[0] * seconds, from[1] + (to[1] - from[1]) * u + rate[1] * seconds,
                from[2] + (to[2] - from[2]) * u + rate[2] * seconds, roll);
        matrix(roll[0], roll[1], roll[2], roll[3], r);
        // place · stretch · roll, then the instance's turn
        matrix(place[0], place[1], place[2], place[3], m);
        for (int c = 0; c < 3; c++) for (int i = 0; i < 3; i++) m[c * 3 + i] *= scale[c];
        multiply(m, r, t);
        matrix(qx, qy, qz, qw, m);
        multiply(m, t, a);
        invert(a, t);
        for (int i = 0; i < 3; i++) for (int c = 0; c < 3; c++) out[i * 3 + c] = t[c * 3 + i];
        for (int c = 0; c < 3; c++) {
            float l = (float) Math.sqrt(a[c * 3] * a[c * 3] + a[c * 3 + 1] * a[c * 3 + 1] + a[c * 3 + 2] * a[c * 3 + 2]);
            for (int i = 0; i < 3; i++) out[9 + i * 3 + c] = l > 0f ? a[c * 3 + i] / l : 0f;
        }
    }

    /** A frame being worked out, a thread's own: instances write on the render thread and the books' workers. */
    private static final class Scratch {
        final float[] roll = new float[4], m = new float[9], r = new float[9], a = new float[9], t = new float[9];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** A unit quaternion's rotation, column-major. */
    private static void matrix(float x, float y, float z, float w, float[] m) {
        m[0] = 1f - 2f * (y * y + z * z);
        m[1] = 2f * (x * y + z * w);
        m[2] = 2f * (x * z - y * w);
        m[3] = 2f * (x * y - z * w);
        m[4] = 1f - 2f * (x * x + z * z);
        m[5] = 2f * (y * z + x * w);
        m[6] = 2f * (x * z + y * w);
        m[7] = 2f * (y * z - x * w);
        m[8] = 1f - 2f * (x * x + y * y);
    }

    /** {@code a · b} into {@code out}, column-major. */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 3; i++) {
                out[c * 3 + i] = a[i] * b[c * 3] + a[3 + i] * b[c * 3 + 1] + a[6 + i] * b[c * 3 + 2];
            }
        }
    }

    /** {@code a}'s inverse into {@code out}, column-major; a box with no extent on an axis maps nothing there. */
    private static void invert(float[] a, float[] out) {
        float c00 = a[4] * a[8] - a[7] * a[5], c01 = a[7] * a[2] - a[1] * a[8], c02 = a[1] * a[5] - a[4] * a[2];
        float det = a[0] * c00 + a[3] * c01 + a[6] * c02;
        float s = det != 0f ? 1f / det : 0f;
        out[0] = c00 * s;
        out[1] = c01 * s;
        out[2] = c02 * s;
        out[3] = (a[6] * a[5] - a[3] * a[8]) * s;
        out[4] = (a[0] * a[8] - a[6] * a[2]) * s;
        out[5] = (a[3] * a[2] - a[0] * a[5]) * s;
        out[6] = (a[3] * a[7] - a[6] * a[4]) * s;
        out[7] = (a[6] * a[1] - a[0] * a[7]) * s;
        out[8] = (a[0] * a[4] - a[3] * a[1]) * s;
    }
}
