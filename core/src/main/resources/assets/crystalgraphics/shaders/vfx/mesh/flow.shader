// Energy flowing over a mesh's UVs: a slash, a swirl, a dome, a shock ring (Effekseer's UV-scroll and alpha-erosion
// material). Noise scrolls along the UVs and is eroded into streaks, white-hot where a reveal sweeps along u and along
// ridges, thinning toward v's edges, brighter edge-on. CgProceduralMesh's UVs: u round or along, v from 1 at the
// profile's start to 0 at its end. Added, so overlapping meshes pile up into light, and it blooms.
// cg_VfxMesh.values: x how far the reveal has swept along u (past 1 the whole mesh shows), y a strength, z how much the
// part behind the reveal fades (0 none), w unused.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory VfxMesh  PerDrawValues On }

Properties {
    _Color    ("Its body; its alpha a strength", color) = (0.2, 0.6, 1.6, 1)
    _Hot      ("The reveal's edge and the streaks", color) = (0.9, 0.97, 1.6, 1)
    _Scroll   ("How fast the noise scrolls along u and v, UV a second", vec4) = (1.2, 0, 0, 0)
    _Tiling   ("Noise cells across u and v", vec4) = (6, 1.5, 0, 0)
    _Erosion  ("Erosion threshold, 0..1: higher eats more", float) = 0.42
    _Edges    ("How much it thins toward v's edges, 0..1", float) = 0.8
    _Rim      ("Brightening edge-on", float) = 0.6
    _GlowGain ("Its glow past white, since the scene blooms only what passes it", float) = 2.0
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; vec3 normal; vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    float age = cg_Vfx.age, seed = cg_Vfx.seed;
    float reveal = cg_VfxMesh.values.x, trail = cg_VfxMesh.values.z;
    float behind = reveal - i.uv0.x;
    if (behind < 0.0) discard;
    float head = exp(-behind * 14.0) * step(reveal, 1.0);
    float tail = mix(1.0, smoothstep(0.0, 0.5, 1.0 - behind), trail);
    vec2 q = i.uv0 * _Tiling.xy - _Scroll.xy * age;
    float body = 0.5 + 0.5 * fx_fbm(vec3(q, seed * 7.0), 3);
    float streak = pow(fx_ridged(vec3(q * vec2(1.0, 3.0), seed * 3.0 + age * 0.4), 3), 5.0);
    float across = 1.0 - _Edges * pow(abs(i.uv0.y * 2.0 - 1.0), 2.0);
    float e = body * across + streak * 0.45 + head * 0.6;
    float aa = fwidth(e) + 0.002;
    float alpha = smoothstep(_Erosion - aa, _Erosion + aa, e);
    vec3 ray = normalize(i.world - CG_CAMERA_POSITION);
    float rim = 1.0 - abs(dot(normalize(i.normal), ray));
    float hot = clamp(head + streak * 1.5, 0.0, 1.0);
    vec3 light = mix(_Color.rgb, _Hot.rgb, hot) * (1.0 + _Rim * rim);
    float strength = alpha * tail * across * cg_VfxMesh.values.y * _Color.a * fx_flicker(age, seed);
    s.baseColor = light * strength;
    s.emissive = _Hot.rgb * hot * strength * _GlowGain;
}
