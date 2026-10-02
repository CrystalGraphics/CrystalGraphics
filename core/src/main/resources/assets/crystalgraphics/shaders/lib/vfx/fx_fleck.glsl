// Flecks thrown out of a burst: stateless CgVfxRibbons particles, each ribbon one fleck on a camera-facing quad. What a
// fleck shader shares: where it flies, and how its quad faces the eye. Nothing here names cg_* or CG_*.
//
//     vec4 h = fx_hash41(index * 1.37 + seed * 53.0);
//     vec3 offset = fx_fleck_flight(h, t, speed, drag, gravity, lift);   // from the burst's centre, in its units
//     vec3 world = fx_fleck_corner(centre + offset * scale, corner, vec2(length, width), angle, right, up);
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

// Where a fleck is t seconds after its burst, from its four hashes h. It leaves over the sphere, biased upward by lift
// (0 any direction, 1 mostly up), at speed times 0.4..1.6. Drag slows all of its motion, so gravity (+y up, negative
// to rise) brings it to a terminal speed of gravity / drag rather than accelerating it for ever.
vec3 fx_fleck_flight(vec4 h, float t, float speed, float drag, float gravity, float lift) {
    float y = mix(-1.0, 1.0, mix(h.x, sqrt(h.x), lift));
    float a = h.y * 6.28318531, across = sqrt(max(1.0 - y * y, 0.0));
    vec3 dir = vec3(across * cos(a), y, across * sin(a));
    float v = speed * (0.4 + 1.2 * h.z);
    if (drag <= 0.0) return dir * v * t + vec3(0.0, -0.5 * gravity * t * t, 0.0);
    float slowed = (1.0 - exp(-drag * t)) / drag;
    return dir * v * slowed + vec3(0.0, -gravity / drag * (t - slowed), 0.0);
}

// A corner of a fleck's quad around centre: corner is -1..1 on each axis, scaled by size (its half-length along x and
// half-width along y, so a fleck can be a sliver or a streak), then turned by angle in the eye's plane.
vec3 fx_fleck_corner(vec3 centre, vec2 corner, vec2 size, float angle, vec3 right, vec3 up) {
    float c = cos(angle), s = sin(angle);
    vec2 scaled = corner * size;
    vec2 turned = vec2(c * scaled.x - s * scaled.y, s * scaled.x + c * scaled.y);
    return centre + right * turned.x + up * turned.y;
}

// The angle in the eye's plane a fleck is moving at, t seconds after its burst: what a streak lines up with.
float fx_fleck_heading(vec4 h, float t, float speed, float drag, float gravity, float lift, vec3 right, vec3 up) {
    vec3 motion = fx_fleck_flight(h, t + 0.02, speed, drag, gravity, lift) - fx_fleck_flight(h, t, speed, drag, gravity, lift);
    return atan(dot(motion, up), dot(motion, right) + 1.0e-6);
}
