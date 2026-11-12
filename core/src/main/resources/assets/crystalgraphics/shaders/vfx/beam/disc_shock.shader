// An energy wave's shock ring at the release: a crisp leading edge sweeping outward with a softer wake behind it, its
// radius wavering, fading as it spreads. Drawn on its sphere flattened to a disc facing along the aim; only the half
// facing the eye. cg_VfxMesh.values.z is an intensity, .w the ring's progress 0..1. _CoreRim is the edge, its alpha a
// strength; _Shell the wake. Its light blooms. CgEnergyWave.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory VfxMesh  PerDrawValues On }

Properties {
    _CoreRim  ("The leading edge; its alpha a strength", color) = (0.7, 0.95, 1, 1)
    _Shell    ("The wake behind it", color) = (0.15, 0.55, 1.6, 1)
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec2 local; };

void vertex(inout CgVertex v, out v2f o) {
    o.local = v.position.xy;
}

void surface(in v2f i, inout CgSurface s) {
    vec2 p = i.local;
    float r = length(p);
    float progress = cg_VfxMesh.values.w;
    float edge = progress * (1.0 + 0.015 * fx_noise(vec3(p / max(r, 1.0e-3) * 2.5, cg_Vfx.age * 3.0 + cg_Vfx.seed * 7.0)));
    float d = r - edge;
    float aa = fwidth(r) + 1.0e-4;
    float wake = 0.06 + 0.22 * progress;
    // Crisp outside the edge, fading in over the wake behind it.
    float ring = (1.0 - smoothstep(-aa, aa, d)) * smoothstep(-wake, 0.0, d);
    float line = 1.0 - smoothstep(0.02 + aa, 0.02 + 2.0 * aa, abs(d));
    vec3 col = _Shell.rgb * ring * 0.7 + _CoreRim.rgb * line * 1.6;
    s.baseColor = col * _CoreRim.a * cg_VfxMesh.values.z * (1.0 - smoothstep(0.98, 1.0, r));
    s.emissive = s.baseColor;
}
