// A smoke trail: what a meteor, a falling fireball or a ki blast through dust leaves behind. Thin and dense at its
// head, billowing wider and thinner as it ages, its edge torn by rolling noise that drifts up and outward, lit by the
// world's light and the sun with a darker underside, hot at its head where _Glow says (a fire's own smoke).
// Blended over what is behind it. A Cascade trail's material; its renderer tiles UV1 once a block (fx_strip.glsl).
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_strip.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color    ("The smoke; its alpha its density", color) = (0.42, 0.4, 0.38, 0.85)
    _Shadow   ("Its underside and thick middle", color) = (0.16, 0.15, 0.15, 1)
    _Glow     ("The glow at its head; alpha 0 for none", color) = (1.6, 0.6, 0.15, 0)
    _Spread   ("Its width at its life's end, times its head's", float) = 3.5
    _Scale    ("Billows a block", float) = 0.9
    _Rise     ("How fast it drifts up as it ages, blocks a second", float) = 0.8
    _Fade     ("How fast it thins along its life", float) = 1.1
    _Noise  ("Noise", sampler3D) = "cg_noise"
}

// life 0..1, across -1..1, blocks along, seconds old; how far up this edge is
struct v2f { vec4 trail; float up; };

void vertex(inout CgVertex v, out v2f o) {
    vec3 trail = fx_strip(v.uv0, v.uv1, cg_Particle.relativeTime);
    float age = CG_PARTICLE_AGE(cg_Particle.record), u = trail.x;
    // Billowing out as it ages, eased so its head stays tight; this vertex's edge lies along -side.
    float spread = mix(1.0, _Spread, 1.0 - (1.0 - u) * (1.0 - u)) - 1.0;
    vec3 out_ = -cg_SpriteAxes[0] * trail.y;
    v.world += out_ * (cg_Particle.positionAndSize.w * spread) + vec3(0.0, _Rise * age, 0.0);
    o.trail = vec4(trail, age);
    o.up = out_.y;
}

void surface(in v2f i, inout CgSurface s) {
    float u = i.trail.x, across = i.trail.y, age = i.trail.w;
    vec3 p = vec3(i.trail.z * _Scale, across * 1.4 - age * 0.6, cg_Vfx.seed * 5.0) + vec3(0.0, 0.0, age * 0.35);
    float billow = 0.5 + 0.5 * fx_fbm(p, 4);
    float detail = 0.5 + 0.5 * fx_fbm(p * 2.7 + 3.1, 3);
    float edge = 1.0 - across * across;
    float density = edge * mix(billow, detail, 0.35) * 1.6 - 0.35 - u * 0.5;
    float aa = fwidth(density) + 1.0e-3;
    float alpha = smoothstep(-aa, 0.35 + aa, density) * pow(1.0 - u, _Fade) * _Color.a * cg_Particle.color.a;
    if (alpha < 0.003) discard;
    // Lit from above and by the sun, darker where it is thick and underneath.
    float top = 0.5 + 0.5 * i.up;
    float sun = 0.55 + 0.45 * max(CG_SUN_DIRECTION.y, 0.0) * CG_DAYLIGHT;
    vec3 colour = mix(_Shadow.rgb, _Color.rgb, clamp(0.35 + 0.5 * billow * top + 0.25 * detail, 0.0, 1.0)) * sun;
    vec3 hot = _Glow.rgb * (_Glow.a * exp(-u * 7.0) * edge) * alpha;
    s.baseColor = colour * alpha + hot;   // premultiplied
    s.opacity = alpha;
    s.emissive = hot;
}
