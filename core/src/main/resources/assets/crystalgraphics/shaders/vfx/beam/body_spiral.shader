// An energy wave's spirals: two sets of bright bands wrapping the body in opposite senses, flowing forward, crisp-edged
// and broken up so no band runs unbroken, faded in from the muzzle and out before the head. A tube layer. _Spiral is
// the bands, its alpha a strength; _Core where they cross. Its light blooms. CgEnergyWave.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Tube  PerDrawValues On }

Properties {
    _Spiral   ("The bands and bolts; its alpha a strength", color) = (0.5, 0.85, 1.6, 1)
    _Core     ("Where the bands cross", color) = (1, 1, 1, 1)
    _Pitch  ("Blocks per turn", float) = 2.6
    _Speed  ("How fast the bands flow forward, turns a second", float) = 5.5
    _Width  ("Band half-width, share of a turn", float) = 0.09
    _Noise ("Noise", sampler3D) = "cg_noise"
}

// A crisp band where u crosses a whole number; aa is u's screen-space rate, worked out in the fragment stage.
float band(float u, float width, float aa) {
    float d = abs(fract(u) - 0.5);
    return 1.0 - smoothstep(width - aa, width + aa, 0.5 - d);
}

struct v2f { vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    float arc = cg_Tube.ring.arc, angle = cg_Tube.angle, age = cg_Tube.header.w;
    float a = angle * 6.28318531;
    // Bands writhing as they race forward, torn open by ridged gaps.
    vec3 q = vec3((arc - age * 24.0) * 0.35, cos(a) * 1.4, sin(a) * 1.4);
    float writhe = fx_noise(q * 1.3 + 4.0) * 0.3;
    float u1 = angle * 2.0 + arc / _Pitch - age * _Speed + writhe;
    float u2 = -angle * 3.0 + arc / (_Pitch * 0.7) - age * _Speed * 1.35 - writhe;
    float b1 = band(u1, _Width, fwidth(u1) + 1.0e-4), b2 = band(u2, _Width * 0.8, fwidth(u2) + 1.0e-4);
    float tear = fx_ridged(q * 1.8 + 9.0, 2);
    float breakup = smoothstep(0.38, 0.55, 0.5 + 0.5 * fx_noise(q)) * (1.0 - smoothstep(0.72, 0.86, tear));
    // How far the head is beyond this ring
    float ends = smoothstep(1.0, 3.5, arc) * smoothstep(0.5, 2.5, cg_Tube.header.y - arc);
    vec3 col = _Spiral.rgb * (b1 + 0.7 * b2) * 1.6 + _Core.rgb * (b1 * b2 * 2.0 + b1 * 0.35);
    s.baseColor = col * breakup * ends * fx_flicker(age, 0.71) * _Spiral.a * (gl_FrontFacing ? 1.0 : 0.4);
    s.emissive = s.baseColor;
}
