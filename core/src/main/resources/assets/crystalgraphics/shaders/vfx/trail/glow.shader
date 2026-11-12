// A ki streak: a strip of energy, white-hot down its middle and its own colour out to soft edges (Effekseer's track,
// centre colour over side colours), energy flowing back along it in streaks and eaten away toward its tail as it fades.
// _Wobble shakes its line across, more toward the tail: energy too wild to hold one. Added, so crossing streaks pile
// into light, and it blooms. A ribbon's material: a Cascade trail behind a moving thing, or a ribbon through particles;
// its renderer tiles UV1 once a block (fx_strip.glsl), and its emitter's size over life tapers it.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_strip.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color      ("Its energy; its alpha a strength", color) = (0.25, 0.65, 1.8, 1)
    _Core       ("The white-hot middle", color) = (1.2, 1.25, 1.4, 1)
    _CoreWidth  ("The middle's share of the width", float) = 0.28
    _Softness   ("How soft its edges are: higher thins them", float) = 1.6
    _Fade       ("How fast it fades along its life: higher sooner", float) = 1.4
    _Tiling     ("Noise cells a block along it, and across it", vec4) = (0.9, 1.3, 0, 0)
    _Flow       ("How fast its energy flows back from the head, blocks a second", float) = 7.0
    _Erode      ("How much its tail is eaten away, 0..1", float) = 0.75
    _Wobble     ("How far its line shakes across, times its width", float) = 0.0
    _WobbleRate ("How fast it shakes, a second", float) = 9.0
    _GlowGain   ("Its glow past white", float) = 1.6
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

// life 0..1, across -1..1, blocks along
struct v2f { vec3 trail; };

void vertex(inout CgVertex v, out v2f o) {
    o.trail = fx_strip(v.uv0, v.uv1, cg_Particle.relativeTime);
    if (_Wobble > 0.0) {
        float shake = fx_noise(vec3(o.trail.z * 1.7, cg_Vfx.age * _WobbleRate, cg_Vfx.seed * 13.0))
                + 0.5 * fx_noise(vec3(o.trail.z * 4.3, cg_Vfx.age * _WobbleRate * 2.1, cg_Vfx.seed * 7.0));
        v.world += cg_SpriteAxes[0] * (cg_Particle.positionAndSize.w * shake * _Wobble * (0.25 + o.trail.x));
    }
}

void surface(in v2f i, inout CgSurface s) {
    FxGlow g = FxGlow(_Color.rgb, _Core.rgb, _CoreWidth, _Softness, _Fade, _Tiling.xy, _Flow, _Erode);
    vec3 glow;
    vec3 light = fx_strip_glow(g, _Noise, i.trail, cg_Vfx.age, cg_Vfx.seed, glow);
    if (dot(light, light) == 0.0) discard;
    vec3 tint = cg_Particle.color.rgb * cg_Particle.color.a * _Color.a;
    s.baseColor = light * tint;
    s.emissive = glow * tint * _GlowGain;
}
