// Spatiotemporal blue noise (CgBlueNoise): EA SEED's FAST noise (Donnelly et al., "Filter-adapted spatiotemporal
// sampling for real-time rendering", i3D 2024; BSD-3), a 128x128x32 volume, a frame a slice, blue under a 3x3 binomial
// filter in space and an exponential moving average in time. Bind it with a property defaulting to "cg_blue_noise":
//     _BlueNoise ("Blue noise", sampler3D) = "cg_blue_noise"
// Each use of the noise in one shader takes its own stream: the same pixel read at a distant texel, which FAST's noise
// leaves uncorrelated after a few texels.

#define CG_BLUE_NOISE_SIZE 128
#define CG_BLUE_NOISE_FRAMES 32

ivec3 cg_blue_noise_texel(ivec2 px, int frame, int stream) {
    ivec2 at = (px + ivec2(stream * 47, stream * 89)) & (CG_BLUE_NOISE_SIZE - 1);
    return ivec3(at, frame & (CG_BLUE_NOISE_FRAMES - 1));
}

// A value in [0, 1) for pixel px this frame, from stream `stream`.
float cg_blue_noise(sampler3D noise, ivec2 px, int frame, int stream) {
    return (texelFetch(noise, cg_blue_noise_texel(px, frame, stream), 0).r * 255.0 + 0.5) / 256.0;
}

// Two values in [0, 1), jointly blue: a point in the unit square.
vec2 cg_blue_noise2(sampler3D noise, ivec2 px, int frame, int stream) {
    return (texelFetch(noise, cg_blue_noise_texel(px, frame, stream), 0).gb * 255.0 + 0.5) / 256.0;
}

// Which full-resolution pixel of its block downsampled pixel d stands for this frame, for a factor of 1x1, 2x1 or 2x2.
// 2x1 alternates a checkerboard each frame. 2x2 is a 4-rooks Latin square over each 2x2 of downsampled pixels: in
// every frame the four take the block's four positions, no two in a row or column, and over four frames each takes
// all four, in the fixed order ROOKS lists.
ivec2 cg_downsample_offset(ivec2 d, ivec2 factor, int frame) {
    if (factor.x < 2) return ivec2(0);
    if (factor.y < 2) return ivec2((d.y + frame) & 1, 0);
    const ivec2 ROOKS[4] = ivec2[4](ivec2(0, 1), ivec2(0, 0), ivec2(1, 1), ivec2(1, 0));
    return ROOKS[((d.x & 1) + 2 * (d.y & 1) + frame) & 3];
}
