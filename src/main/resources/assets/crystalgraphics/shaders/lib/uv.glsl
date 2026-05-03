#pragma once
#include "crystalgraphics:shaders/lib/math.glsl"

// Rotate UV around pivot by angle (radians)
vec2 rotate_uv(vec2 uv, float angle, vec2 pivot) {
    float s = sin(angle);
    float c = cos(angle);
    uv -= pivot;
    uv  = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
    return uv + pivot;
}
vec2 rotate_uv(vec2 uv, float angle) { return rotate_uv(uv, angle, vec2(0.5)); }

// Scale around pivot (>1 zooms out, <1 zooms in)
vec2 scale_uv(vec2 uv, vec2 scale, vec2 pivot) { return (uv - pivot) / scale + pivot; }
vec2 scale_uv(vec2 uv, vec2   scale) { return scale_uv(uv, scale, vec2(0.5)); }
vec2 scale_uv(vec2 uv, float  scale) { return scale_uv(uv, vec2(scale), vec2(0.5)); }

vec2 tile_uv(vec2 uv, vec2 tile, vec2 offset) { return uv * tile + offset; }
vec2 tile_uv(vec2 uv, vec2 tile) { return uv * tile; }

vec2 pan_uv(vec2 uv, vec2 speed, float time) { return uv + speed * time; }

vec2 flip_uv_x(vec2 uv) { return vec2(1.0 - uv.x, uv.y); }
vec2 flip_uv_y(vec2 uv) { return vec2(uv.x, 1.0 - uv.y); }

// Input: UV centered at 0.5. Returns (r, theta) each normalized to [0,1].
// r=0 at center; theta=0..1 wrapping full circle.
vec2 cartesian_to_polar_uv(vec2 uv) {
    vec2 c = uv - 0.5;
    float r     = length(c) * 2.0;
    float theta = atan(c.y, c.x) / CG_TWO_PI + 0.5;
    return vec2(r, theta);
}
