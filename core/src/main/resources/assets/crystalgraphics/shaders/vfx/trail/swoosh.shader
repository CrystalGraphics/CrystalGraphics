// A blade's swoosh: the sheet a swing sweeps between its base and its tip, as action games draw a sword's or a fist's
// arc. Brightest along the tip's edge, where it is newest, in a hot line; streaks of light running along the swing,
// thinning toward the base and eaten away toward the tail; faded where it is seen edge-on, so it never shows as a
// line. Added, and it blooms. An anim trail's material: its edge's first point the base, its second the tip.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color    ("Its sheet; its alpha a strength", color) = (0.35, 0.7, 1.8, 1)
    _Hot      ("Its leading edge and streaks", color) = (1.3, 1.35, 1.5, 1)
    _Base     ("How far out from the base it starts to show, 0..1", float) = 0.25
    _Edge     ("How tight its hot edge is: higher thinner", float) = 14.0
    _Decay    ("How fast it dims behind the swing: higher sooner", float) = 2.4
    _Tiling   ("Streak cells along the swing's life, and across from base to tip", vec4) = (3.5, 7.0, 0, 0)
    _Flow     ("How fast its streaks run along it, a second", float) = 2.5
    _Erode    ("How much its tail is eaten away, 0..1", float) = 0.85
    _GlowGain ("Its glow past white", float) = 1.8
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec2 uv0; vec3 normal; vec3 world; };

void surface(in v2f i, inout CgSurface s) {
    float u = cg_Particle.relativeTime, outward = i.uv0.y;
    float sheet = smoothstep(_Base, 1.0, outward);
    float edge = exp(-(1.0 - outward) * _Edge) * exp(-u * 4.0);
    vec3 q = vec3(u * _Tiling.x - cg_Vfx.age * _Flow, outward * _Tiling.y, cg_Vfx.seed * 11.0);
    float streak = pow(fx_ridged(q, 3), 3.0);
    float n = 0.5 + 0.5 * fx_fbm(q * vec3(0.5, 1.0, 1.0), 3);
    float e = sheet * (0.5 + 0.5 * n) + streak * 0.6 * sheet + edge - u * u * _Erode;
    float alpha = smoothstep(0.0, 0.25, e);
    float behind = exp(-u * _Decay);
    // Edge-on, a sheet would draw as a bright line: it fades to nothing there. The eye is the origin.
    float facing = smoothstep(0.05, 0.4, abs(dot(normalize(i.normal), normalize(-i.world + vec3(1.0e-6)))));
    float strength = alpha * behind * facing * cg_Particle.color.a * _Color.a;
    float hot = clamp(edge * 1.5 + streak * 0.7 * (1.0 - u), 0.0, 1.0);
    s.baseColor = mix(_Color.rgb * (0.6 + 0.6 * n), _Hot.rgb, hot) * strength;
    s.emissive = (_Hot.rgb * hot + _Color.rgb * sheet * 0.3) * strength * _GlowGain;
}
