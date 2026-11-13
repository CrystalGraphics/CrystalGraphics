// An anime streak: flat bands across a strip, as cel animation paints ki (white core, a bright band, the colour at its
// edge), each band narrowing toward the tail so the outer ones run out first, the tail breaking into hard brushed
// streaks rather than fading, an optional ink edge. The banded toon look (toon/banded.shader) on a ribbon. _Bands binds
// the look's `bands`.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/strip.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Bands    ("Its core, its middle band and its edge", color[3]) = (1, 1, 1, 1), (0.55, 0.9, 1.6, 1), (0.1, 0.45, 1.5, 1)
    _Widths   ("Where the core and the middle band end across it, 0..1", vec4) = (0.32, 0.64, 0, 0)
    _Ink      ("Its ink edge; alpha 0 for none", color) = (0.02, 0.03, 0.08, 0)
    _InkWidth ("The ink's share of the width", float) = 0.12
    _Break    ("How far up from its tail it breaks into streaks, 0..1", float) = 0.55
    _Streaks  ("Streaks across it", float) = 5.0
    _GlowGain ("Its core's glow past white", float) = 1.2
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec2 uv0; vec2 uv1; };

void surface(in v2f i, inout CgSurface s) {
    vec3 trail = fx_strip(i.uv0, i.uv1, cg_Particle.relativeTime);
    float u = trail.x, r = abs(trail.y);
    // Its tail breaks into streaks: each strand across it runs out at its own point along it.
    float strand = fx_value_noise(vec3(floor(trail.y * _Streaks * 0.5 + 0.5 * _Streaks) * 1.7, cg_Vfx.seed * 31.0, 0.5));
    float runsOut = 1.0 - _Break * (0.35 + 0.65 * strand);
    float aa = fwidth(r) + 1.0e-3;
    float shape = 1.0 - smoothstep(1.0 - aa, 1.0, r);
    float uaa = fwidth(u) + 1.0e-3;
    float broken = u < 1.0 - _Break ? 1.0 : 1.0 - smoothstep(runsOut - uaa, runsOut + uaa, u);
    float cover = shape * broken;
    if (cover <= 0.0) discard;
    // Each band narrows toward the tail, the outer ones first.
    float w0 = _Widths.x * (1.0 - 0.5 * u), w1 = mix(_Widths.y, _Widths.x, u * 0.8);
    float inCore = 1.0 - smoothstep(w0 - aa, w0 + aa, r);
    float inMiddle = 1.0 - smoothstep(w1 - aa, w1 + aa, r);
    vec3 colour = mix(mix(_Bands(2).rgb, _Bands(1).rgb, inMiddle), _Bands(0).rgb, inCore);
    float ink = _Ink.a * smoothstep(1.0 - _InkWidth - aa, 1.0 - _InkWidth + aa, r);
    colour = mix(colour, _Ink.rgb, ink);
    float alpha = cover * cg_Particle.color.a;
    s.baseColor = colour * alpha;   // premultiplied
    s.opacity = alpha;
    s.emissive = _Bands(0).rgb * inCore * (1.0 - ink) * alpha * _GlowGain;
}
