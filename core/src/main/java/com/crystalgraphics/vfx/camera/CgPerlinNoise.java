package com.crystalgraphics.vfx.camera;

/**
 * Unreal's 1D Perlin noise, {@code FMath::PerlinNoise1D} (Engine/Source/Runtime/Core/Private/Math/UnrealMath.cpp):
 * Ken Perlin's improved noise over Unreal's permutation table, with sixteen 1D gradient scales. In (-1, 1), 0 at every
 * integer. Ported so a shake samples exactly what Unreal's does for the same offsets.
 *
 * <pre>{@code
 * float n = CgPerlinNoise.noise1D(offset);   // offset advances by dt * frequency each update
 * }</pre>
 */
public final class CgPerlinNoise {

    /** Unreal's permutation of 0..255, repeated, so {@code i + 1} never wraps. */
    private static final int[] PERMUTATION = new int[512];
    private static final int[] TABLE = {
            63, 9, 212, 205, 31, 128, 72, 59, 137, 203, 195, 170, 181, 115, 165, 40, 116, 139, 175, 225, 132, 99, 222, 2,
            41, 15, 197, 93, 169, 90, 228, 43, 221, 38, 206, 204, 73, 17, 97, 10, 96, 47, 32, 138, 136, 30, 219,
            78, 224, 13, 193, 88, 134, 211, 7, 112, 176, 19, 106, 83, 75, 217, 85, 0, 98, 140, 229, 80, 118, 151, 117,
            251, 103, 242, 81, 238, 172, 82, 110, 4, 227, 77, 243, 46, 12, 189, 34, 188, 200, 161, 68, 76, 171, 194,
            57, 48, 247, 233, 51, 105, 5, 23, 42, 50, 216, 45, 239, 148, 249, 84, 70, 125, 108, 241, 62, 66, 64, 240, 173,
            185, 250, 49, 6, 37, 26, 21, 244, 60, 223, 255, 16, 145, 27, 109, 58, 102, 142, 253, 120, 149, 160,
            124, 156, 79, 186, 135, 127, 14, 121, 22, 65, 54, 153, 91, 213, 174, 24, 252, 131, 192, 190, 202, 208, 35, 94,
            231, 56, 95, 183, 163, 111, 147, 25, 67, 36, 92, 236, 71, 166, 1, 187, 100, 130, 143, 237, 178, 158,
            104, 184, 159, 177, 52, 214, 230, 119, 87, 114, 201, 179, 198, 3, 248, 182, 39, 11, 152, 196, 113, 20, 232, 69,
            141, 207, 234, 53, 86, 180, 226, 74, 150, 218, 29, 133, 8, 44, 123, 28, 146, 89, 101, 154, 220, 126,
            155, 122, 210, 168, 254, 162, 129, 33, 18, 209, 61, 191, 199, 157, 245, 55, 164, 167, 215, 246, 144, 107, 235
    };
    private static final float[] GRADIENTS = {-8 / 8f, -7 / 8f, -6 / 8f, -5 / 8f, -4 / 8f, -3 / 8f, -2 / 8f, -1 / 8f,
            1 / 8f, 2 / 8f, 3 / 8f, 4 / 8f, 5 / 8f, 6 / 8f, 7 / 8f, 8 / 8f};

    static {
        for (int i = 0; i < 512; i++) PERMUTATION[i] = TABLE[i & 255];
    }

    private CgPerlinNoise() {
    }

    /** Noise at {@code x}, in (-1, 1). */
    public static float noise1D(float x) {
        float floor = (float) Math.floor(x);
        int xi = (int) floor & 255;
        x -= floor;
        float xm1 = x - 1f;
        float a = GRADIENTS[PERMUTATION[xi] & 15] * x;
        float b = GRADIENTS[PERMUTATION[xi + 1] & 15] * xm1;
        float u = x * x * x * (x * (x * 6f - 15f) + 10f);
        return 2f * (a + (b - a) * u);
    }
}
