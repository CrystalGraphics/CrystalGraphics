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

// Unity's Polar Coordinates NODE, ported exactly. Deliberately NOT cartesian_to_polar_uv above, which
// answers a different question and whose convention is right for what it is: that one wraps theta into
// [0,1] so a UV can be re-tiled INTO polar space, which needs a continuous, always-positive coordinate.
//
// Unity's node keeps the angle SIGNED, and that is the whole visual character of its preview: with the
// output displayed as (r, theta, 0), every texel whose angle is negative clamps its green channel to
// zero and reads pure RED, so the thumbnail splits down the middle — red on one side, green/yellow on
// the other, dark at the centre where the radius vanishes. Routing the node through the [0,1] helper
// made theta positive everywhere, which flooded the whole preview green/yellow with no red at all and
// put the branch cut on the wrong axis.
//
// Two differences from the helper, and both matter:
//   * atan(delta.x, delta.y), NOT atan(y, x) — Unity passes x first, which rotates the discontinuity
//     from the -x axis onto the -y axis (a seam pointing DOWN rather than LEFT).
//   * no +0.5 offset — see above.
// CG_TWO_PI rather than Unity's own literal 6.28: the same constant to more digits, imperceptible here
// and consistent with the rest of this file.
vec2 polar_coordinates_uv(vec2 uv, vec2 center, float radialScale, float lengthScale) {
    vec2 delta = uv - center;
    float radius = length(delta) * 2.0 * radialScale;
    float angle  = atan(delta.x, delta.y) / CG_TWO_PI * lengthScale;
    return vec2(radius, angle);
}

// Standard node-graph UV distortions — the same shape every node-based shader editor (Unity, Unreal,
// Godot) ships, not independently verified against Unity's own page (none of the three were among the
// fifteen nodes this project verified in detail). Added for CgBuiltinShaderNodes' Twirl/Radial
// Shear/Spherize, same reasoning rotate_uv/tile_uv already existed for Rotate/Tiling and Offset.

// Rotates uv around center by an angle proportional to distance from center — a swirl distortion.
// `strength` is radians of rotation per unit distance.
vec2 twirl_uv(vec2 uv, vec2 center, float strength, vec2 offset) {
    vec2 delta = uv - center;
    float angle = strength * length(delta);
    float s = sin(angle);
    float c = cos(angle);
    return vec2(c * delta.x - s * delta.y, s * delta.x + c * delta.y) + center + offset;
}

// Shears uv tangentially around center, proportional to squared distance — a pinwheel-like distortion.
vec2 radial_shear_uv(vec2 uv, vec2 center, vec2 strength, vec2 offset) {
    vec2 delta = uv - center;
    float d2 = dot(delta, delta);
    return uv + vec2(delta.y, -delta.x) * d2 * strength + offset;
}

// Pinches/bulges uv toward center, as if the image were wrapped onto a sphere.
vec2 spherize_uv(vec2 uv, vec2 center, float strength, vec2 offset) {
    vec2 delta = uv - center;
    float d2 = clamp(dot(delta, delta), 0.0, 1.0);
    float z = sqrt(1.0 - d2);
    return uv - delta * (1.0 - z) * strength + offset;
}
