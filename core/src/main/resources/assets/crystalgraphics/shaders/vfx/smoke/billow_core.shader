// A billow's depth wherever it is solid, and nothing else: billow.shader's own shape and density (fx_billow.glsl), drawn
// before anything else in its effect (CgVfxLayer.ORDER_DEPTH), a transparent depth prepass. Where it is solid only its
// nearest surface is shaded, and its own back lobes, the billows and debris behind it and the blast are hidden whatever
// order they draw in; through its thin edge and its holes they show. Written a few depth steps farther than the
// surface, so billow.shader's pass, computing the same surface in another program, never fails its depth test against
// it: steps, not blocks, since a step spans distance squared over the near plane and a fixed distance z-fights afar.
// CG_OBJECT_CUSTOM1 as billow.shader's.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_billow.glsl"

Tags { "RenderType" = "Transparent" "CastShadows" = "Off" "Lighting" = "Unlit" }
Queue = "Transparent"

// billow.shader's shape and density: change them in both.
Properties {
    _Cells   ("Lobes around the sphere, cells per unit", float) = 1.5
    _Bulge   ("Lobe height, share of the radius", float) = 0.4
    _Fine    ("Small domes on the lobes, share of the bulge", float) = 0.3
    _Boil    ("How far the lobes drift over its life, in cells", float) = 0.5
    _Edge    ("How far in from the silhouette it thins, as how far the surface turns from the eye", float) = 0.45
    _Wisp    ("How much noise eats its edge", float) = 0.9
    _WispScale ("Wisp noise, cycles per unit", float) = 3.2
    _Fade    ("How far over its rest it fades into the surface it rests on, share of its size", float) = 0.3
    _NearFrom ("Blocks from the eye where it starts dissolving", float) = 8.0
    _NearTo  ("Blocks from the eye where it is gone", float) = 2.0
    _Rest    ("Height it rests at over a surface, share of its size", float) = 0.08
    _Round   ("How far over that the squash rounds off, share of its size", float) = 0.45
    _Spread  ("How far it spreads along the surface for each block it is pushed off it", float) = 0.3
    _Solid   ("How opaque it must be to hide what is behind it", float) = 0.9
    _Behind  ("How far behind the surface its depth is written, in normalized depth: a few 24-bit steps", float) = 5.0e-7
    _Noise ("Noise", sampler3D) = "cg_noise"
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _VoronoiNearest ("Cells", sampler3D) = "cg_voronoi_nearest"
}

struct v2f { vec3 world; vec3 normal; vec3 local; vec2 lobe; float above; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        ColorMask 0
        DepthWrite ON
        DepthTest LEQUAL
        Blend OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y, opacity = CG_OBJECT_CUSTOM1.z;
        vec3 p = normalize(cg_Position);
        vec3 boil = vec3(0.0, life * _Boil, life * _Boil * 0.6), gLarge, gFine, n;
        float large = fx_billow_dome(fx_voronoi_nearest(p * _Cells + vec3(seed * 31.0) + boil), _Cells, gLarge);
        float fine = fx_billow_dome(fx_voronoi_nearest(p * _Cells * 2.2 + vec3(seed * 17.0 + 5.0) - boil * 1.8),
                _Cells * 2.2, gFine);
        float h = fx_billow_height(p, large, gLarge, fine, gFine, _Bulge, _Fine, n);
        vec3 world = (CG_OBJECT_TO_WORLD * vec4(p * h * (0.25 + 0.75 * sqrt(opacity)), 1.0)).xyz;
        vec3 normal = normalize(mat3(CG_OBJECT_TO_WORLD) * n);
        o.above = fx_billow_squash(world, normal, CG_OBJECT_TO_WORLD[3].xyz, length(CG_OBJECT_TO_WORLD[0].xyz),
                CG_OBJECT_SPARE, _Rest, _Round, _Spread);
        o.world = world;
        o.normal = normal;
        o.local = p * h;
        o.lobe = vec2(large, fx_value_noise(p * 1.6 + seed * 9.0));
        vec4 clip = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
        // Farther is larger depth, or smaller where depth is reversed: ask the projection which.
        vec4 beyond = cg_ProjMatrix * cg_ViewMatrix * vec4(world + normalize(world - FX_CAMERA), 1.0);
        clip.z += sign(beyond.z / beyond.w - clip.z / clip.w) * _Behind * clip.w;
        gl_Position = clip;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y, opacity = CG_OBJECT_CUSTOM1.z;
        float facing = max(dot(normalize(i.normal), normalize(FX_CAMERA - i.world)), 0.0);
        float wisp = 0.5 + 0.5 * fx_fbm(i.local * _WispScale + vec3(0.0, -life * 2.5, 0.0) + seed * 7.0, 3);
        float near = 1.0 - smoothstep(_NearTo, _NearFrom, distance(FX_CAMERA, i.world));
        float alpha = fx_billow_density(facing, wisp, i.lobe.y, i.lobe.x, _Edge, _Wisp, max(1.0 - opacity, near));
        alpha *= smoothstep(_Rest, _Rest + _Fade, i.above);
        if (alpha < _Solid) discard;
        fragColor = vec4(0.0);
    }
}
