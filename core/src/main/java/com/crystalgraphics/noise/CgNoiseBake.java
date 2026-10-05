package com.crystalgraphics.noise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The engine's noise volumes baked on the CPU: tiling RGBA cubes of the noise {@code fx_common.glsl} computes per
 * pixel, so a shader reads one texel instead of hashing eight corners. Each channel is the same noise at the same
 * frequency with its own seed, so one fetch gives four independent values, and an fbm's octaves read different
 * channels and never line up.
 *
 * <pre>{@code
 * float[] gradient = CgNoiseBake.gradient(64, 16);   // 64^3 texels, the lattice tiling every 16 cells
 * ByteBuffer texels = CgNoiseBake.toHalf(gradient);  // RGBA16F, slices bottom row first
 * }</pre>
 *
 * <ul>
 *   <li>A volume holds {@code period} lattice cells across; a shader samples it at {@code p / period}, with
 *       {@code GL_REPEAT}. Keep {@code size / period} at 4 texels or more, or trilinear filtering shows the grid.</li>
 *   <li>At texel centres the values are the GLSL functions' own at lattice coordinate {@code (x + 0.5) * period / size}:
 *       the hashes, the quintic and the scale are ported, the lattice wrapped.</li>
 *   <li>Output is {@code float[size^3 * 4]}, x fastest, then y, then z.</li>
 * </ul>
 */
public final class CgNoiseBake {

    /** Per channel, added to the wrapped lattice cell before hashing: four decorrelated fields from one hash. */
    private static final float[][] SEEDS = {{0f, 0f, 0f}, {131.7f, 47.3f, 19.1f}, {29.3f, 173.9f, 61.7f},
            {83.1f, 11.9f, 157.3f}};

    private CgNoiseBake() {
    }

    /** Gradient noise ({@code fx_noise}), about -1..1, in each of four channels. */
    public static float[] gradient(int size, int period) {
        float[] out = new float[size * size * size * 4];
        fill(out, size, period, (x, y, z, c) -> gradientAt(x, y, z, period, SEEDS[c]));
        return out;
    }

    /** Value noise ({@code fx_value_noise}), 0..1, in each of four channels. */
    public static float[] value(int size, int period) {
        float[] out = new float[size * size * size * 4];
        fill(out, size, period, (x, y, z, c) -> valueAt(x, y, z, period, SEEDS[c]));
        return out;
    }

    /**
     * Cellular noise ({@code fx_voronoi}): r the distance to the nearest feature point, g to the second, both in cells;
     * b the nearest cell's hash, 0..1; a 0.
     */
    public static float[] voronoi(int size, int period) {
        float[] out = new float[size * size * size * 4];
        float[] cell = new float[3];
        float scale = (float) period / size;
        int i = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++, i += 4) {
                    voronoiAt((x + 0.5f) * scale, (y + 0.5f) * scale, (z + 0.5f) * scale, period, cell);
                    out[i] = cell[0];
                    out[i + 1] = cell[1];
                    out[i + 2] = cell[2];
                }
            }
        }
        return out;
    }

    /**
     * Cellular noise with its slope: rgb the unit direction from the nearest feature point, the gradient of the distance
     * to it; a that distance, in cells. What a surface shaded from cellular noise takes its normal from: the gradient
     * interpolates smoothly where differences of a filtered distance would step at every texel.
     */
    public static float[] voronoiNearest(int size, int period) {
        float[] out = new float[size * size * size * 4];
        float[] cell = new float[6];
        float scale = (float) period / size;
        int i = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++, i += 4) {
                    voronoiAt((x + 0.5f) * scale, (y + 0.5f) * scale, (z + 0.5f) * scale, period, cell);
                    out[i] = cell[3];
                    out[i + 1] = cell[4];
                    out[i + 2] = cell[5];
                    out[i + 3] = cell[0];
                }
            }
        }
        return out;
    }

    /**
     * A divergence-free flow: rgb the curl of {@code gradient}'s first three channels taken as a vector potential
     * (Bridson's curl noise), in lattice units, by central differences across texels; a 0. {@code gradient} is
     * {@link #gradient}'s output at the same size and period.
     */
    public static float[] curl(float[] gradient, int size, int period) {
        float[] out = new float[size * size * size * 4];
        float twoH = 2f * period / size;
        int i = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++, i += 4) {
                    // d(channel)/d(axis): the potential's components are channels 0, 1, 2
                    float dzdy = (at(gradient, size, x, y + 1, z, 2) - at(gradient, size, x, y - 1, z, 2)) / twoH;
                    float dydz = (at(gradient, size, x, y, z + 1, 1) - at(gradient, size, x, y, z - 1, 1)) / twoH;
                    float dxdz = (at(gradient, size, x, y, z + 1, 0) - at(gradient, size, x, y, z - 1, 0)) / twoH;
                    float dzdx = (at(gradient, size, x + 1, y, z, 2) - at(gradient, size, x - 1, y, z, 2)) / twoH;
                    float dydx = (at(gradient, size, x + 1, y, z, 1) - at(gradient, size, x - 1, y, z, 1)) / twoH;
                    float dxdy = (at(gradient, size, x, y + 1, z, 0) - at(gradient, size, x, y - 1, z, 0)) / twoH;
                    out[i] = dzdy - dydz;
                    out[i + 1] = dxdz - dzdx;
                    out[i + 2] = dydx - dxdy;
                }
            }
        }
        return out;
    }

    /**
     * The next mip level of a cube of RGBA texels {@code size} on an edge, x fastest: each texel the average of the
     * eight it covers, the box filter GL's {@code glGenerateMipmap} uses. {@code size} is even.
     *
     * <pre>{@code
     * float[] level1 = CgNoiseBake.halve(gradient, 64);   // 32^3
     * }</pre>
     */
    public static float[] halve(float[] texels, int size) {
        int half = size / 2;
        float[] out = new float[half * half * half * 4];
        int i = 0;
        for (int z = 0; z < half; z++) {
            for (int y = 0; y < half; y++) {
                for (int x = 0; x < half; x++, i += 4) {
                    for (int c = 0; c < 4; c++) {
                        float sum = 0f;
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dy = 0; dy < 2; dy++) {
                                int row = ((2 * z + dz) * size + 2 * y + dy) * size + 2 * x;
                                sum += texels[row * 4 + c] + texels[(row + 1) * 4 + c];
                            }
                        }
                        out[i + c] = sum * 0.125f;
                    }
                }
            }
        }
        return out;
    }

    /** {@code texels} as RGBA16F, native order, ready for a {@code GL_HALF_FLOAT} upload. */
    public static ByteBuffer toHalf(float[] texels) {
        ByteBuffer out = ByteBuffer.allocateDirect(texels.length * 2).order(ByteOrder.nativeOrder());
        for (float v : texels) out.putShort(half(v));
        out.flip();
        return out;
    }

    /** Round-to-nearest-even float to IEEE half; overflow saturates to infinity, as GL's conversion does. */
    static short half(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = bits >>> 16 & 0x8000;
        int exponent = (bits >>> 23 & 0xFF) - 127 + 15;
        int mantissa = bits & 0x7FFFFF;
        if ((bits & 0x7FFFFFFF) > 0x7F800000) return (short) (sign | 0x7E00);   // NaN
        if (exponent >= 31) return (short) (sign | 0x7C00);
        if (exponent <= 0) {
            if (exponent < -10) return (short) sign;
            mantissa |= 0x800000;
            int shift = 14 - exponent;
            int rounded = mantissa >> shift;
            int rest = mantissa & ((1 << shift) - 1), halfway = 1 << (shift - 1);
            if (rest > halfway || (rest == halfway && (rounded & 1) != 0)) rounded++;
            return (short) (sign | rounded);
        }
        int rounded = exponent << 10 | mantissa >> 13;
        int rest = mantissa & 0x1FFF;
        if (rest > 0x1000 || (rest == 0x1000 && (rounded & 1) != 0)) rounded++;
        return (short) (sign | rounded);
    }

    // ── The ported GLSL ──────────────────────────────────────────────────────────────────────────────────────────

    private interface Field {
        float at(float x, float y, float z, int channel);
    }

    private static void fill(float[] out, int size, int period, Field field) {
        float scale = (float) period / size;
        int i = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float px = (x + 0.5f) * scale, py = (y + 0.5f) * scale, pz = (z + 0.5f) * scale;
                    for (int c = 0; c < 4; c++) out[i++] = field.at(px, py, pz, c);
                }
            }
        }
    }

    private static float at(float[] texels, int size, int x, int y, int z, int channel) {
        x = Math.floorMod(x, size);
        y = Math.floorMod(y, size);
        z = Math.floorMod(z, size);
        return texels[((z * size + y) * size + x) * 4 + channel];
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    /** {@code fx_hash31}. */
    static float hash31(float px, float py, float pz) {
        px = fract(px * 0.1031f);
        py = fract(py * 0.1031f);
        pz = fract(pz * 0.1031f);
        float d = px * (pz + 31.32f) + py * (py + 31.32f) + pz * (px + 31.32f);
        px += d;
        py += d;
        pz += d;
        return fract((px + py) * pz);
    }

    /** {@code fx_hash33}, into {@code out}. */
    static void hash33(float px, float py, float pz, float[] out) {
        px = fract(px * 0.1031f);
        py = fract(py * 0.1030f);
        pz = fract(pz * 0.0973f);
        float d = px * (py + 33.33f) + py * (px + 33.33f) + pz * (pz + 33.33f);
        px += d;
        py += d;
        pz += d;
        out[0] = fract((px + py) * pz);
        out[1] = fract((px + px) * py);
        out[2] = fract((py + px) * px);
    }

    private static float wrap(float cell, int period) {
        return (float) Math.floorMod((int) cell, period);
    }

    private static float quintic(float f) {
        return f * f * f * (f * (f * 6f - 15f) + 10f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final ThreadLocal<float[]> SCRATCH = ThreadLocal.withInitial(() -> new float[3]);

    /** {@code fx_corner}: the wrapped corner's gradient dotted with the offset to it. */
    private static float corner(float ix, float iy, float iz, float fx, float fy, float fz, int cx, int cy, int cz,
                                int period, float[] seed) {
        float[] g = SCRATCH.get();
        hash33(wrap(ix + cx, period) + seed[0], wrap(iy + cy, period) + seed[1], wrap(iz + cz, period) + seed[2], g);
        return (g[0] * 2f - 1f) * (fx - cx) + (g[1] * 2f - 1f) * (fy - cy) + (g[2] * 2f - 1f) * (fz - cz);
    }

    /** {@code fx_noise} on a lattice wrapping every {@code period} cells. */
    static float gradientAt(float px, float py, float pz, int period, float[] seed) {
        float ix = (float) Math.floor(px), iy = (float) Math.floor(py), iz = (float) Math.floor(pz);
        float fx = px - ix, fy = py - iy, fz = pz - iz;
        float ux = quintic(fx), uy = quintic(fy), uz = quintic(fz);
        float c000 = corner(ix, iy, iz, fx, fy, fz, 0, 0, 0, period, seed);
        float c100 = corner(ix, iy, iz, fx, fy, fz, 1, 0, 0, period, seed);
        float c010 = corner(ix, iy, iz, fx, fy, fz, 0, 1, 0, period, seed);
        float c110 = corner(ix, iy, iz, fx, fy, fz, 1, 1, 0, period, seed);
        float c001 = corner(ix, iy, iz, fx, fy, fz, 0, 0, 1, period, seed);
        float c101 = corner(ix, iy, iz, fx, fy, fz, 1, 0, 1, period, seed);
        float c011 = corner(ix, iy, iz, fx, fy, fz, 0, 1, 1, period, seed);
        float c111 = corner(ix, iy, iz, fx, fy, fz, 1, 1, 1, period, seed);
        return 1.6f * lerp(lerp(lerp(c000, c100, ux), lerp(c010, c110, ux), uy),
                lerp(lerp(c001, c101, ux), lerp(c011, c111, ux), uy), uz);
    }

    /** {@code fx_value_noise} on a lattice wrapping every {@code period} cells. */
    static float valueAt(float px, float py, float pz, int period, float[] seed) {
        float ix = (float) Math.floor(px), iy = (float) Math.floor(py), iz = (float) Math.floor(pz);
        float ux = quintic(px - ix), uy = quintic(py - iy), uz = quintic(pz - iz);
        float x0 = wrap(ix, period) + seed[0], x1 = wrap(ix + 1, period) + seed[0];
        float y0 = wrap(iy, period) + seed[1], y1 = wrap(iy + 1, period) + seed[1];
        float z0 = wrap(iz, period) + seed[2], z1 = wrap(iz + 1, period) + seed[2];
        return lerp(lerp(lerp(hash31(x0, y0, z0), hash31(x1, y0, z0), ux), lerp(hash31(x0, y1, z0), hash31(x1, y1, z0), ux), uy),
                lerp(lerp(hash31(x0, y0, z1), hash31(x1, y0, z1), ux), lerp(hash31(x0, y1, z1), hash31(x1, y1, z1), ux), uy), uz);
    }

    /**
     * {@code fx_voronoi} on cells wrapping every {@code period}: F1, F2 and the nearest cell's hash into {@code out}, and
     * into {@code out[3..5]}, when it holds six, the unit direction from the nearest feature point.
     */
    static void voronoiAt(float px, float py, float pz, int period, float[] out) {
        float cx = (float) Math.floor(px), cy = (float) Math.floor(py), cz = (float) Math.floor(pz);
        float fx = px - cx, fy = py - cy, fz = pz - cz;
        float f1 = 8f, f2 = 8f, id = 0f, nx = 0f, ny = 0f, nz = 0f;
        float[] h = new float[3];
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    float wx = wrap(cx + x, period), wy = wrap(cy + y, period), wz = wrap(cz + z, period);
                    hash33(wx, wy, wz, h);
                    float dx = x + h[0] - fx, dy = y + h[1] - fy, dz = z + h[2] - fz;
                    float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d < f1) {
                        f2 = f1;
                        f1 = d;
                        id = hash31(wx, wy, wz);
                        nx = dx;
                        ny = dy;
                        nz = dz;
                    } else if (d < f2) {
                        f2 = d;
                    }
                }
            }
        }
        out[0] = f1;
        out[1] = f2;
        out[2] = id;
        if (out.length >= 6) {
            // (nx, ny, nz) runs from p to the feature point; the distance grows away from it
            float inv = f1 > 1e-6f ? -1f / f1 : 0f;
            out[3] = nx * inv;
            out[4] = ny * inv;
            out[5] = nz * inv;
        }
    }
}
