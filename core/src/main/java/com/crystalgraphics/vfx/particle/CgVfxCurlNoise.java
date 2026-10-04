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
 * <p>Each component of the result is roughly -2..2. Thread-safe: it keeps no state.</p>
 */
public final class CgVfxCurlNoise {

    private static final float OCTAVE = 2.03f;
    // Perlin's improved-noise gradients: the twelve cube edges, four of them twice to fill sixteen.
    private static final float[] GX = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, -1, 0, 0};
    private static final float[] GY = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, 1, -1, -1};
    private static final float[] GZ = {0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 0, 1, -1};

    private CgVfxCurlNoise() {
    }

    /** The field at {@code (x, y, z)}, in the noise's own units, into {@code out[0..2]}. */
    public static void sample(float x, float y, float z, float[] out) {
        // The three potentials share each octave's lattice cell and weights; one hash per corner picks all three
        // gradients. Only their gradients are kept, which is all the curl reads.
        float ay = 0f, az = 0f, bx = 0f, bz = 0f, cx = 0f, cy = 0f;
        float px = x, py = y, pz = z, k = 1f;
        for (int octave = 0; octave < 2; octave++) {
            if (octave == 1) {
                px = x * OCTAVE + 5.2f;
                py = y * OCTAVE + 1.3f;
                pz = z * OCTAVE + 7.9f;
                k = 0.5f * OCTAVE;
            }
            int ix = floor(px), iy = floor(py), iz = floor(pz);
            float fx = px - ix, fy = py - iy, fz = pz - iz;
            float ux = fade(fx), uy = fade(fy), uz = fade(fz);
            float dux = k * fadeSlope(fx), duy = k * fadeSlope(fy), duz = k * fadeSlope(fz);
            for (int c = 0; c < 8; c++) {
                int ox = c & 1, oy = (c >> 1) & 1, oz = (c >> 2) & 1;
                int h = hash(ix + ox, iy + oy, iz + oz);
                float rx = fx - ox, ry = fy - oy, rz = fz - oz;
                float wx = ox == 1 ? ux : 1f - ux, wy = oy == 1 ? uy : 1f - uy, wz = oz == 1 ? uz : 1f - uz;
                float sx = ox == 1 ? dux : -dux, sy = oy == 1 ? duy : -duy, sz = oz == 1 ? duz : -duz;
                // gradient of the weighted corner (w * dot): w * g + dot * grad(w), the octave's scale folded into both
                float w = k * wx * wy * wz, wdx = sx * wy * wz, wdy = wx * sy * wz, wdz = wx * wy * sz;
                int g = h >>> 28;
                float dot = GX[g] * rx + GY[g] * ry + GZ[g] * rz;
                ay += w * GY[g] + wdy * dot;
                az += w * GZ[g] + wdz * dot;
                g = (h >>> 24) & 15;
                dot = GX[g] * rx + GY[g] * ry + GZ[g] * rz;
                bx += w * GX[g] + wdx * dot;
                bz += w * GZ[g] + wdz * dot;
                g = (h >>> 20) & 15;
                dot = GX[g] * rx + GY[g] * ry + GZ[g] * rz;
                cx += w * GX[g] + wdx * dot;
                cy += w * GY[g] + wdy * dot;
            }
        }
        // curl of (A, B, C): (dC/dy - dB/dz, dA/dz - dC/dx, dB/dx - dA/dy)
        out[0] = cy - bz;
        out[1] = az - cx;
        out[2] = bx - ay;
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
        h *= 0x297A2D39;
        return h;
    }
}
