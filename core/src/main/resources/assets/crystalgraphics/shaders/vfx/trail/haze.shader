// The air a fast thing tears through: the scene behind a strip shimmering in its wake, strongest down its middle and
// near its head, gone at its edges and its tail, rippling as it streams back. Draws nothing in the scene, only bends
// what is behind it (s.refraction), so the streak it trails stays sharp. A ribbon's material, widening by half again
// toward its tail; its renderer tiles UV1 once a block (fx_strip.glsl).
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_strip.glsl"
Material { BlendMode Translucent  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Strength  ("Its bend at full strength, share of the screen's height", float) = 0.03
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the strip does on screen", float) = 6
    _Scale     ("Ripples a block", float) = 1.8
    _Stream    ("How fast the ripples stream back, blocks a second", float) = 4.0
    _Fade      ("How fast it calms along its life", float) = 1.2
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

// life 0..1, across -1..1, blocks along; where it is, from the eye
struct v2f { vec3 trail; vec3 world; };

void vertex(inout CgVertex v, out v2f o) {
    o.trail = fx_strip(v.uv0, v.uv1, cg_Particle.relativeTime);
    v.world += cg_SpriteAxes[0] * (cg_Particle.positionAndSize.w * 0.6 * o.trail.x * -o.trail.y);
    o.world = v.world;
}

void surface(in v2f i, inout CgSurface s) {
    float u = i.trail.x, across = abs(i.trail.y);
    float body = (1.0 - across * across) * pow(1.0 - u, _Fade) * smoothstep(0.0, 0.05, u + 0.02);
    float strength = body * cg_Particle.color.a;
    if (strength < 0.002) discard;
    vec3 p = vec3((i.trail.z + cg_Vfx.age * _Stream) * _Scale, i.trail.y * 1.5, cg_Vfx.seed * 7.0);
    vec2 wobble = fx_heat(p, cg_Vfx.age, _Stream * _Scale * 0.2, cg_Vfx.seed);
    float far = min(1.0, _Reference / max(length(i.world), 1.0e-3));
    s.refraction = wobble * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
    s.refractionSplit = 0.15 * strength;
}
