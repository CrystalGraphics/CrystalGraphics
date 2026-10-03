package com.crystalgraphics.compute.ops;

/**
 * Random numbers without state, the Java twin of {@code crystalgraphics:shaders/lib/rng.glsl}: four words hashed
 * from (seed, element, step, stream), the same bits a kernel draws, so a kernel's Java body draws what the kernel
 * does. PCG4D, from Jarzynski and Olano, "Hash Functions for GPU Rendering" (JCGT 2020).
 *
 * <pre>{@code
 * int[] r = new int[4];                                  // once: nothing here allocates
 * CgRng.rng4(seed, particle.id, step, 0, r);             // the element's id, never its slot
 * float u = CgRng.unit(r[0]);                            // [0, 1), the bits cg_rng_unit gives
 * CgRng.direction(r[1], r[2], out);                      // float math from there on: within an ulp of the GPU's
 * }</pre>
 *
 * <ul>
 *   <li>Ints are GLSL's uints: Java's wrapping multiply is the same arithmetic, and shifts are {@code >>>}.</li>
 *   <li>Key an element by an id it carries. Append and compaction move records between slots, and a slot's
 *       numbers would change with them.</li>
 *   <li>A new stream, not a new step, for a second use in the same step: they are independent words.</li>
 * </ul>
 */
public final class CgRng {

    private CgRng() {}

    /** The four words for (seed, element, step, stream), into {@code out}. */
    public static int[] rng4(int seed, int element, int step, int stream, int[] out) {
        int x = seed * 1664525 + 1013904223;
        int y = element * 1664525 + 1013904223;
        int z = step * 1664525 + 1013904223;
        int w = stream * 1664525 + 1013904223;
        x += y * w; y += z * x; z += x * y; w += y * z;
        x ^= x >>> 16; y ^= y >>> 16; z ^= z >>> 16; w ^= w >>> 16;
        x += y * w; y += z * x; z += x * y; w += y * z;
        out[0] = x;
        out[1] = y;
        out[2] = z;
        out[3] = w;
        return out;
    }

    /** The first of the four words: {@code cg_rng}. */
    public static int rng(int seed, int element, int step, int stream) {
        int x = seed * 1664525 + 1013904223;
        int y = element * 1664525 + 1013904223;
        int z = step * 1664525 + 1013904223;
        int w = stream * 1664525 + 1013904223;
        x += y * w; y += z * x; z += x * y; w += y * z;
        x ^= x >>> 16; y ^= y >>> 16; z ^= z >>> 16; w ^= w >>> 16;
        return x + y * w;
    }

    /** The top 24 bits as a float in [0, 1), exactly as {@code cg_rng_unit}. */
    public static float unit(int bits) {
        return (bits >>> 8) * (1f / 16777216f);
    }

    /** A direction uniform over the sphere, from two words, into {@code out}: {@code cg_rng_direction}. */
    public static float[] direction(int a, int b, float[] out) {
        float z = 1f - 2f * unit(a);
        float angle = 6.28318530718f * unit(b);
        float r = (float) Math.sqrt(Math.max(0f, 1f - z * z));
        out[0] = r * (float) Math.cos(angle);
        out[1] = r * (float) Math.sin(angle);
        out[2] = z;
        return out;
    }
}
