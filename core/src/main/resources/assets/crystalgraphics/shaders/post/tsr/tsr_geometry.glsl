// The depth and motion TSR's first two passes read (CgTemporalSuperResolution), from their properties: _Input the render
// size and its inverse; _Jitter this frame's jitter in input pixels; _Depth x, y the projection's terms taking a device depth to a view depth (eye = x / (d + y)), z a
// view depth's pixel radius per unit depth (tan of half the FOV over the render width); _ClipToPrev0-3 this frame's
// unjittered clip space to last frame's, by column. After the file's Properties.
#pragma once

#include "crystalgraphics:shaders/post/tsr/tsr.glsl"

// -- Depth -------------------------------------------------------------------------------------------------------
// The engine's depth is reversed: nearer is greater, the far plane 0.

float tsr_eye(float device) {
    return _Depth.x / max(device + _Depth.y, 1.0e-12);
}

float tsr_device(float eye) {
    return _Depth.x / max(eye, 1.0e-12) - _Depth.y;
}

// How far a pixel spans in the world at view depth eye, from its centre.
float tsr_pixel_radius(float eye) {
    return _Depth.z * eye;
}

// The device depth step a pixel's world size makes at device depth d: what two depths within one pixel's span differ by.
float tsr_pixel_depth_error(float d) {
    float eye = tsr_eye(d);
    return abs(tsr_device(eye + 2.0 * tsr_pixel_radius(eye)) - d);
}

// Where this frame's unjittered clip point (screen, device depth) was last frame, as a screen position and depth.
vec3 tsr_previous(vec2 screen, float device) {
    vec4 p = _ClipToPrev0 * screen.x + _ClipToPrev1 * screen.y + _ClipToPrev2 * device + _ClipToPrev3;
    return p.xyz / p.w;
}

// A pixel's motion as its screen position this frame less last frame's, and its device depth's: what the velocity pass
// wrote, less this frame's jitter it measured from (its jittered position against last frame's unjittered one), else the
// camera's own over the depth (a still surface). _Jitter is in input pixels.
vec3 tsr_velocity(vec2 encoded, vec2 screen, float device) {
    if (encoded.x > 0.0) return vec3(cg_DecodeVelocity(encoded) - _Jitter.xy * 2.0 * _Input.zw, 0.0);
    return vec3(screen, device) - tsr_previous(screen, device);
}

// -- Hole filling ----------------------------------------------------------------------------------------------------
// A pixel velocity in 18 bits, 5 of angle and 13 of length in 32nds of a pixel, below a depth's 14 half-float bits in
// one uint: the farthest-first maximum of the scatter keeps the nearest surface, and its motion with it.

const uint TSR_HOLE_BITS = 18u, TSR_HOLE_LENGTH_BITS = 13u;
const float TSR_HOLE_LENGTH_UNITS = 32.0, TSR_PI = 3.14159265;

uint tsr_hole_encode(vec2 pixelVelocity) {
    const float angles = float(1u << (TSR_HOLE_BITS - TSR_HOLE_LENGTH_BITS));
    const uint lengths = 1u << TSR_HOLE_LENGTH_BITS;
    float angle = atan(pixelVelocity.y, pixelVelocity.x) * (0.5 * angles / TSR_PI) + 0.5 * angles;
    uint a = uint(round(angle)) & uint(angles - 1.0);
    uint l = min(uint(ceil(length(pixelVelocity) * TSR_HOLE_LENGTH_UNITS)), lengths - 1u);
    return a * lengths + l;
}

void tsr_hole_decode(uint encoded, out float angle, out float len) {
    const uint angles = 1u << (TSR_HOLE_BITS - TSR_HOLE_LENGTH_BITS), lengths = 1u << TSR_HOLE_LENGTH_BITS;
    len = float(encoded % lengths) / TSR_HOLE_LENGTH_UNITS;
    angle = float((encoded >> TSR_HOLE_LENGTH_BITS) % angles) * (TSR_PI * 2.0 / float(angles)) - TSR_PI;
}

float tsr_hole_max_length() {
    return (float(1u << TSR_HOLE_LENGTH_BITS) - 1.5) / TSR_HOLE_LENGTH_UNITS;
}

uint tsr_scatter_value(float prevDevice, vec2 pixelVelocity) {
    return (packHalf2x16(vec2(prevDevice, 0.0)) << TSR_HOLE_BITS) | tsr_hole_encode(pixelVelocity);
}

float tsr_scatter_depth(uint value) {
    return unpackHalf2x16(value >> TSR_HOLE_BITS).x;
}

