package com.crystalgraphics.vfx.particle;

/**
 * Curl noise: a turbulent velocity field with no divergence, so particles carried by it swirl as air does instead of
 * bunching or scattering. The curl of three gradient-noise potentials, two octaves of each, after Bridson, Hourihan and
 * Nordenstam, "Curl-Noise for Procedural Fluid Flow" (SIGGRAPH 2007), the field Niagara's Curl Noise Force samples.
 *
 * <pre>{@code
 * float[] v = new float[3];                   // held, so sampling allocates nothing
 * CgVfxCurlNoise.sample(x * 0.1f, y * 0.1f, z * 0.1f, v);
 * ax += v[0] * strength;
 * }</pre>
 *
 * <p>Each component of the result is roughly -2..2. Not thread-safe: it reuses a scratch array.</p>
 */
public final class CgVfxCurlNoise {

    private static final float[] A = new float[4], B = new float[4], C = new float[4];

    private CgVfxCurlNoise() {
    }

    /** The field at {@code (x, y, z)}, in the noise's own units, into {@code out[0..2]}. */
    public static void sample(float x, float y, float z, float[] out) {
        potential(x, y, z, 0f, 0f, 0f, A);
        potential(x, y, z, 31.4f, 47.1f, 12.9f, B);
        potential(x, y, z, 73.7f, 19.3f, 55.1f, C);
        // curl of (A, B, C): (dC/dy - dB/dz, dA/dz - dC/dx, dB/dx - dA/dy)
        out[0] = C[2] - B[3];
        out[1] = A[3] - C[1];
        out[2] = B[1] - A[2];
    }

    /** Two octaves of gradient noise at p plus an offset: value in d[0], its gradient in d[1..3]. */
    private static void potential(float x, float y, float z, float ox, float oy, float oz, float[] d) {
        gradient(x + ox, y + oy, z + oz, d);
        float v = d[0], gx = d[1], gy = d[2], gz = d[3];
        gradient(x * 2.03f + ox + 5.2f, y * 2.03f + oy + 1.3f, z * 2.03f + oz + 7.9f, d);
        d[0] = v + 0.5f * d[0];
        d[1] = gx + 0.5f * 2.03f * d[1];
        d[2] = gy + 0.5f * 2.03f * d[2];
        d[3] = gz + 0.5f * 2.03f * d[3];
    }

    /**
     * Gradient noise at p with its analytic derivative: d[0] the value, about -1..1, d[1..3] its gradient. Quintic fade,
     * as fx_noise in the shaders, so the field is smooth to its second derivative.
     */
    static void gradient(float x, float y, float z, float[] d) {
        int ix = floor(x), iy = floor(y), iz = floor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        float ux = fade(fx), uy = fade(fy), uz = fade(fz);
        float dux = fadeSlope(fx), duy = fadeSlope(fy), duz = fadeSlope(fz);
        float value = 0f, gx = 0f, gy = 0f, gz = 0f;
        for (int c = 0; c < 8; c++) {
            int cx = c & 1, cy = (c >> 1) & 1, cz = (c >> 2) & 1;
            int h = hash(ix + cx, iy + cy, iz + cz);
            float rx = fx - cx, ry = fy - cy, rz = fz - cz;
            float hx = gradX(h), hy = gradY(h), hz = gradZ(h);
            float dot = hx * rx + hy * ry + hz * rz;
            float wx = cx == 1 ? ux : 1f - ux, wy = cy == 1 ? uy : 1f - uy, wz = cz == 1 ? uz : 1f - uz;
            float sx = cx == 1 ? dux : -dux, sy = cy == 1 ? duy : -duy, sz = cz == 1 ? duz : -duz;
            float w = wx * wy * wz;
            value += w * dot;
            gx += w * hx + sx * wy * wz * dot;
            gy += w * hy + wx * sy * wz * dot;
            gz += w * hz + wx * wy * sz * dot;
        }
        d[0] = value;
        d[1] = gx;
        d[2] = gy;
        d[3] = gz;
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float fadeSlope(float t) {
        return 30f * t * t * (t * (t - 2f) + 1f);
    }

    private static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static int hash(int x, int y, int z) {
        int h = x * 0x8DA6B343 ^ y * 0xD8163841 ^ z * 0xCB1AB31F;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    // One of the twelve edge directions of a cube, as Perlin's improved noise picks them.
    private static float gradX(int h) {
        int k = Math.floorMod(h, 12);
        return k < 4 ? ((k & 1) == 0 ? 1f : -1f) : k < 8 ? ((k & 1) == 0 ? 1f : -1f) : 0f;
    }

    private static float gradY(int h) {
        int k = Math.floorMod(h, 12);
        return k < 4 ? ((k & 2) == 0 ? 1f : -1f) : k < 8 ? 0f : ((k & 1) == 0 ? 1f : -1f);
    }

    private static float gradZ(int h) {
        int k = Math.floorMod(h, 12);
        return k < 4 ? 0f : k < 8 ? ((k & 2) == 0 ? 1f : -1f) : ((k & 2) == 0 ? 1f : -1f);
    }
}
