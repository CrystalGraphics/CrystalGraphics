package com.crystalgraphics.api.mesh;

/**
 * Improved Perlin noise and its curl, for deforming generated meshes: ported from Effekseer's {@code PerlinNoise} and
 * {@code CurlNoise} (MIT, {@code Dev/Cpp/Effekseer/Effekseer/Noise}), its permutation shuffled by the same generator,
 * so a seed gives Effekseer's field.
 */
final class CgMeshNoise {

    private final int[] x, y, z;

    /** Three fields seeded as Effekseer's {@code CurlNoise(seed, ...)} seeds them. */
    CgMeshNoise(int seed) {
        x = permutation(seed);
        y = permutation(seed * (seed % 1949 + 5));
        z = permutation(seed * (seed % 3541 + 10));
    }

    private static int[] permutation(int seed) {
        int[] p = new int[512];
        for (int i = 0; i < 256; i++) p[i] = i;
        int s = seed;
        for (int i = 0; i < 256; i++) {
            s = (s * 1103515245 + 12345) & 0x7FFFFFFF;
            int target = (int) ((float) (s % 0x7FFF) / (float) (0x7FFF - 1) * 255f);
            int t = p[i];
            p[i] = p[target];
            p[target] = t;
        }
        System.arraycopy(p, 0, p, 256, 256);
        return p;
    }

    /** The curl of the three fields' two-octave noise at {@code (px, py, pz)}, into {@code out}. */
    void curl(float px, float py, float pz, float[] out) {
        float e = 1f / 1024f;
        float dzdy = octave(z, px, py + e, pz) - octave(z, px, py - e, pz);
        float dydz = octave(y, px, py, pz + e) - octave(y, px, py, pz - e);
        float dxdz = octave(x, px, py, pz + e) - octave(x, px, py, pz - e);
        float dzdx = octave(z, px + e, py, pz) - octave(z, px - e, py, pz);
        float dydx = octave(y, px + e, py, pz) - octave(y, px - e, py, pz);
        float dxdy = octave(x, px, py + e, pz) - octave(x, px, py - e, pz);
        float k = 1f / (2f * e);
        out[0] = (dzdy - dydz) * k;
        out[1] = (dxdz - dzdx) * k;
        out[2] = (dydx - dxdy) * k;
    }

    /** Two octaves, in 0..1. */
    private static float octave(int[] p, float px, float py, float pz) {
        return (noise(p, px, py, pz) + 0.5f * noise(p, px * 2f, py * 2f, pz * 2f)) * 0.5f + 0.5f;
    }

    private static float noise(int[] p, float px, float py, float pz) {
        float fx = (float) Math.floor(px), fy = (float) Math.floor(py), fz = (float) Math.floor(pz);
        int xi = (int) fx & 0xFF, yi = (int) fy & 0xFF, zi = (int) fz & 0xFF;
        float x = px - fx, y = py - fy, z = pz - fz;
        float u = fade(x), v = fade(y), w = fade(z);
        int a0 = p[xi] + yi, a1 = p[a0] + zi, a2 = p[a0 + 1] + zi;
        int b0 = p[xi + 1] + yi, b1 = p[b0] + zi, b2 = p[b0 + 1] + zi;
        float g000 = lerp(u, grad(p[a1], x, y, z), grad(p[b1], x - 1, y, z));
        float g010 = lerp(u, grad(p[a2], x, y - 1, z), grad(p[b2], x - 1, y - 1, z));
        float g001 = lerp(u, grad(p[a1 + 1], x, y, z - 1), grad(p[b1 + 1], x - 1, y, z - 1));
        float g011 = lerp(u, grad(p[a2 + 1], x, y - 1, z - 1), grad(p[b2 + 1], x - 1, y - 1, z - 1));
        return lerp(w, lerp(v, g000, g010), lerp(v, g001, g011));
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    private static float grad(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
