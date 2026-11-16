// What every TSR kernel shares (CgTemporalSuperResolution): the colour spaces the history is compared and kept in, its
// dither, and its filters. Pixels are rows bottom first, as every target of the engine's: a pixel's screen position is
// its centre's normalized device coordinates. The depth and motion of the first two passes: tsr_geometry.glsl.
#pragma once

#include "crystalgraphics:shaders/lib/velocity.glsl"

// The largest a float of the history's R11G11B10 holds (its 10-bit channel's top).
const float TSR_MAX_10BIT_FLOAT = 64512.0;
// The least luma box, in the shading space, whose aliasing is worth anti-aliasing spatially.
const float TSR_MIN_ALIASING_LUMA = 0.05;

vec2 tsr_screen(vec2 pixel, vec4 size) {
    return (pixel + 0.5) * size.zw * 2.0 - 1.0;
}

// -- Colour spaces -------------------------------------------------------------------------------------------------
// The guide is kept in x / (x + 0.17), a gamma-like curve close to a tonemapper's, so its 10 bits are spent where the
// eye sees; the rejection measures in its square, linear enough not to over-reject the shadows.

vec3 tsr_to_guide(vec3 linear) {
    return linear / (linear + 0.17);
}

vec3 tsr_from_guide(vec3 g) {
    return g * min(0.17 / max(1.0 - g, 1.0e-6), 65504.0);
}

vec3 tsr_guide_to_shading(vec3 g) {
    return g * g;
}

vec3 tsr_to_shading(vec3 linear) {
    return tsr_guide_to_shading(tsr_to_guide(linear));
}

// x / (x + 1) and its inverse: the space the guide is blended in.
vec3 tsr_perceptual(vec3 linear) {
    return linear / (linear + 1.0);
}

vec3 tsr_from_perceptual(vec3 c) {
    return c * min(1.0 / max(1.0 - c, 1.0e-6), 65504.0);
}

// Luma times 4, and Karis's tone weight on it (High Quality Temporal Supersampling, 2014): a bright sample weighs less,
// so one firefly does not own the blend.
float tsr_luma4(vec3 c) {
    return c.g * 2.0 + c.r + c.b;
}

float tsr_tone_weight(float luma4) {
    return max(1.0e-4, 1.0 / (luma4 + 4.0));
}

float tsr_tone_weight(vec3 c) {
    return tsr_tone_weight(tsr_luma4(c));
}

// The luma the spatial anti-aliasing follows edges in: Rec. 601's, in x / (x + 0.5).
float tsr_spatial_luma(vec3 linear) {
    float luma = dot(linear, vec3(0.299, 0.587, 0.114));
    return luma / (0.5 + luma);
}

// The spatial anti-aliasing's offset across an edge, -0.5 to 0.5 input pixels an axis, in 4 bits each (x low, y high;
// 8 is no offset, steps of 1/14).
uint tsr_encode_offset(vec2 offset) {
    uvec2 q = uvec2(clamp(ivec2(round(offset * 14.0)) + 8, 1, 15));
    return q.x | (q.y << 4u);
}

vec2 tsr_decode_offset(uint encoded) {
    return (vec2(encoded & 15u, (encoded >> 4u) & 15u) - 8.0) * (1.0 / 14.0);
}

// -- Edges ---------------------------------------------------------------------------------------------------------
// An end-of-edge search (as FXAA's: Lottes, 2009) on any signal: the luma for the spatial anti-aliasing, the depth for
// the reprojection field. The caller fetches the taps (bilinear, halfway onto the edge, step i along the run at i), the
// functions read them.

const int TSR_MOST_EDGE_STEPS = 8;

// From a pixel's 3x3 (c the centre; down, up, left, right; the corners), the axis its edge runs along, the side it lies
// on and the signal there; and how far the pixel stands out of its neighbours alone (noise, not an edge).
void tsr_edge_direction(float c, float down, float up, float left, float right, float downLeft, float downRight,
        float upLeft, float upRight, out float noise, out ivec2 along, out ivec2 side, out float edge) {
    // Second differences across the rows (below, through, above) and across the columns: the edge runs along the
    // axis that varies less
    float acrossRow = abs(0.5 * (left + right) - c), acrossColumn = abs(0.5 * (down + up) - c);
    float rows = abs(0.5 * (downLeft + downRight) - down) + acrossRow + abs(0.5 * (upLeft + upRight) - up);
    float columns = abs(0.5 * (downRight + upRight) - right) + acrossColumn + abs(0.5 * (downLeft + upLeft) - left);
    float dDown = abs(down - c), dUp = abs(up - c), dRight = abs(right - c), dLeft = abs(left - c);
    noise = max(clamp(2.0 * acrossRow - max(dRight, dLeft), 0.0, 1.0), clamp(2.0 * acrossColumn - max(dDown, dUp), 0.0, 1.0));
    bool vertical = rows > columns;
    along = vertical ? ivec2(0, 1) : ivec2(1, 0);
    if (vertical) {
        side = ivec2(dLeft > dRight ? -1 : 1, 0);
        edge = dLeft > dRight ? left : right;
    } else {
        side = ivec2(0, dDown > dUp ? -1 : 1);
        edge = dDown > dUp ? down : up;
    }
}

// The run along the edge each way over steps taps (tapsP[i - 1] i steps one way, tapsN the other): how far it went
// before the tap left the band about the mean of the pixel and its edge side, and whether it left toward the edge's
// side (grow: the edge's side takes over the row) or the pixel's (shrink).
void tsr_edge_run(float c, float edge, float tapsP[TSR_MOST_EDGE_STEPS], float tapsN[TSR_MOST_EDGE_STEPS], int steps,
        out int lengthP, out int lengthN, out bool growP, out bool growN, out bool shrinkP, out bool shrinkN) {
    float mean = 0.5 * (edge + c), band = 0.25 * abs(edge - c);
    float least = mean - band, most = mean + band;
    lengthP = steps;
    lengthN = steps;
    bool lowP = false, highP = false, lowN = false, highN = false;
    for (int i = 0; i < TSR_MOST_EDGE_STEPS; i++) {   // LOOP-BOUND: steps, at most 8
        if (i >= steps) break;
        bool lp = lowP || (tapsP[i] < least && !highP), hp = highP || (tapsP[i] > most && !lowP);
        bool ln = lowN || (tapsN[i] < least && !highN), hn = highN || (tapsN[i] > most && !lowN);
        lowP = lp;
        highP = hp;
        lowN = ln;
        highN = hn;
        if (lowP || highP) lengthP--;
        if (lowN || highN) lengthN--;
    }
    bool brighter = edge > c;
    growP = brighter ? highP : lowP;
    growN = brighter ? highN : lowN;
    shrinkP = brighter ? lowP : highP;
    shrinkN = brighter ? lowN : highN;
}

// How far across its edge a pixel belongs, from where along the run it sits.
float tsr_distance_to_edge(bool growN, bool growP, bool shrinkN, bool shrinkP, int lengthN, int lengthP) {
    float inverse = 1.0 / float(1 + lengthN + lengthP);
    if (!growN && !growP && !shrinkN && !shrinkP) return 0.0;   // no stair in reach
    if (growN && growP) return 0.5;                              // a notch into the edge's side
    if (shrinkN && shrinkP) return 0.0;                          // a bump out of the pixel's side
    if (growN) return 0.5 - (float(lengthN) + 0.5) * inverse;    // a stair rising one way
    if (growP) return 0.5 - (float(lengthP) + 0.5) * inverse;    // or the other
    return 0.0;
}

// The line a depth edge crosses the pixel along, as the vector from the pixel's centre to its nearest point (the
// reprojection field's boundary): which side of it an output pixel lies on says whose motion it takes. From the run's
// ends, the edge's inclination; its distance held between where the pixel's side and the edge's side give out.
vec2 tsr_reprojection_boundary(int steps, ivec2 side, int lengthP, int lengthN, bool growP, bool growN, bool shrinkP,
        bool shrinkN) {
    float invMost = 1.0 / float(steps + 1);
    float fN = float(lengthN), fP = float(lengthP);
    float inverse = 1.0 / (1.0 + fN + fP), wider = max(inverse, invMost);
    float maxDistance = 1.0, maxInclination = 0.0;
    if (shrinkP && shrinkN) {
        maxDistance = (min(fP, fN) + 0.5) * invMost;
        maxInclination = fP < fN ? -invMost : fN < fP ? invMost : 0.0;
    } else if (shrinkP) {
        maxDistance = (fP + 0.5) * wider;
        maxInclination = -wider;
    } else if (shrinkN) {
        maxDistance = (fN + 0.5) * wider;
        maxInclination = wider;
    }
    float minDistance = 0.0, minInclination = 0.0;
    if (growP && growN) {
        minDistance = 1.0 - (min(fP, fN) + 0.5) * invMost;
        minInclination = fP < fN ? invMost : fN < fP ? -invMost : 0.0;
    } else if (growP) {
        minDistance = 1.0 - (fP + 0.5) * wider;
        minInclination = wider;
    } else if (growN) {
        minDistance = 1.0 - (fN + 0.5) * wider;
        minInclination = -wider;
    }
    minDistance = max(minDistance, 1.0 / 1023.0);
    // Dilated to half a pixel, within the two
    const float dilate = 0.5;
    float dist = max(minDistance, min(maxDistance, dilate));
    float inclination = minDistance > dilate ? minInclination : maxDistance <= dilate ? maxInclination : 0.0;
    vec2 s = vec2(side);
    vec2 direction = normalize(s + vec2(abs(s.y), abs(s.x)) * inclination);
    vec2 k = clamp(sign(direction), 0.0, 1.0);
    vec2 e = vec2(0.5) + s * (0.5 - dist);
    vec2 l = k - dot(k - e, direction) * direction;
    return k - l;
}

// The reprojection field's boundary with the neighbour its motion came from ([-1, 1] each axis, 4 bits) in 32 bits:
// 11 bits an axis in 1023ths. TSR_FULL_DILATE where no edge was found: every output pixel takes the neighbour's motion.
const vec2 TSR_FULL_DILATE = vec2(0.0, 1.0);

uint tsr_encode_boundary(ivec2 offset, vec2 boundary) {
    uvec2 o = uvec2(offset + 1);
    uvec2 bits = uvec2(clamp(ivec2(round(boundary * 1023.0)) + 1024, 1, 2047));
    return (o.x + o.y * 4u) | (bits.x << 4u) | (bits.y << 15u);
}

ivec2 tsr_decode_boundary_offset(uint encoded) {
    return ivec2(int(encoded & 3u), int((encoded >> 2u) & 3u)) - 1;
}

vec2 tsr_decode_boundary(uint encoded) {
    ivec2 bits = ivec2(int((encoded >> 4u) & 0x7FFu), int((encoded >> 15u) & 0x7FFu)) - 1024;
    return clamp(vec2(bits) / 1023.0, -1.0, 1.0);
}

// Whether an output pixel at dKO from its nearest input pixel's centre overlaps the boundary's far side, the history
// spanning historyToInput input pixels: there it takes the neighbour's motion.
bool tsr_within_boundary(vec2 dKO, vec2 boundary, float historyToInput) {
    vec2 dKC = 0.5 - vec2(lessThan(boundary, vec2(0.0)));
    vec2 dBR = boundary - dKC + dKO + dKC * historyToInput;
    return dot(dBR, boundary) > 0.0;
}

// The motion's Jacobian (input pixels of motion per input pixel, a row per axis) in 32 bits: 8 bits each, square-rooted
// over [-2, 2] so a small slope keeps its precision.
uint tsr_encode_jacobian(vec2 dx, vec2 dy) {
    const float range = 2.0, scale = 127.0 / range;
    vec4 j = clamp(vec4(dx, dy), -range, range);
    j = sign(j) * sqrt(abs(j)) * sqrt(range);
    uvec4 q = uvec4(scale * j + 127.5);
    return q.x | (q.y << 8u) | (q.z << 16u) | (q.w << 24u);
}

void tsr_decode_jacobian(uint encoded, out vec2 dx, out vec2 dy) {
    const float range = 2.0;
    vec4 j = vec4(uvec4(encoded, encoded >> 8u, encoded >> 16u, encoded >> 24u) & 0xFFu) * (range / 127.0) - range;
    j = j * abs(j) / range;
    dx = j.xy;
    dy = j.zw;
}

// How far the motion stretches a pixel, from its Jacobian: an output pixel spanning more history than one is upscaled.
float tsr_jacobian_upscale(vec2 dx, vec2 dy) {
    vec2 east = vec2(1.0, 0.0) + dx, south = vec2(0.0, 1.0) + dy;
    return sqrt(max(dot(east, east), 1.0) * max(dot(south, south), 1.0));
}

// -- Dither --------------------------------------------------------------------------------------------------------
// pcg3d (Jarzynski and Olano, Hash Functions for GPU Rendering, 2020): 16 random bits for a pixel and frame.

uvec3 tsr_pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v;
}

float tsr_random(ivec2 pixel, int frame) {
    return float(tsr_pcg3d(uvec3(ivec3(pixel, frame))).x & 0xFFFFu) / 65536.0;
}

// A unorm channel of step, rounded up or down at random by e (0 to 1) rather than always down when stored.
vec3 tsr_dither_unorm(vec3 c, float e, float step) {
    return c + step * (e - 0.5);
}

// A float channel's last mantissa bit at c (2^-mantissa of its exponent), times e: rounded at random when truncated.
vec3 tsr_dither_float(vec3 c, float e, vec3 mantissa) {
    vec3 unit = uintBitsToFloat(floatBitsToUint(c * mantissa) & 0xFF800000u);
    return c + unit * e;
}

// -- Kernels -------------------------------------------------------------------------------------------------------

// Bicubic Catmull-Rom of a bilinear texture in five taps (the corners dropped, their weight to the centre: Stubbe's
// form), at uv of a texture of size: each tap's uv into uvs, its weight into weights.
void tsr_catmull_rom(vec2 uv, vec4 size, out vec2 uvs[5], out float weights[5]) {
    vec2 at = uv * size.xy;
    vec2 i = floor(at - 0.5) + 1.0;
    vec2 f = at - i, f2 = f * f, ff = f + f;
    vec2 mid = (1.25 - f2) * f + 0.5;
    vec2 t0 = (0.25 * f2 - 0.0625) * (1.125 - 0.5 * f2.yx);
    vec2 w0 = t0 - ff * t0, w3 = t0 + ff * t0;
    vec2 c = (i - 0.5) * size.zw;
    mid *= size.zw;
    uvs[0] = c + vec2(-size.z, mid.y);
    uvs[1] = c + vec2(2.0 * size.z, mid.y);
    uvs[2] = c + mid;
    uvs[3] = c + vec2(mid.x, -size.w);
    uvs[4] = c + vec2(mid.x, 2.0 * size.w);
    weights[0] = w0.x;
    weights[1] = w3.x;
    weights[2] = 1.0 - w0.x - w3.x - w0.y - w3.y;
    weights[3] = w0.y;
    weights[4] = w3.y;
}

// The weight of an input sample delta pixels from where the output pixel sits, an input pixel spanning upscale output
// pixels: 1 - 1.9 x^2 + 0.9 x^4 of the squared distance, a smooth bump falling to 0 at one output pixel.
float tsr_sample_weight(float upscale, vec2 delta, float least) {
    float x2 = clamp(upscale * upscale * dot(delta, delta), 0.0, 1.0);
    return clamp((0.9 * x2 - 1.9) * x2 + 1.0 + least, 0.0, 1.0);
}
