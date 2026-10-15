// Energy flowing over a mesh's UVs: a slash, a swirl, a dome, a shock ring (Effekseer's UV-scroll and alpha-erosion
// material). Noise scrolls along the UVs and is eroded into streaks, white-hot where a reveal sweeps along u and along
// ridges, thinning toward v's edges, brighter edge-on. CgProceduralMesh's UVs: u round or along, v from 1 at the
// profile's start to 0 at its end. Added, so overlapping meshes pile up into light.
// CG_OBJECT_CUSTOM1: x how far the reveal has swept along u (past 1 the whole mesh shows), y a strength, z how much the
// part behind the reveal fades (0 none), w unused.
#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

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

struct v2f { vec3 world; vec3 normal; vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        o.normal = transpose(inverse(mat3(CG_OBJECT_TO_WORLD))) * cg_Normal;
        o.uv = cg_TexCoord0;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        float reveal = CG_OBJECT_CUSTOM1.x, trail = CG_OBJECT_CUSTOM1.z;
        float behind = reveal - i.uv.x;
        if (behind < 0.0) discard;
        float head = exp(-behind * 14.0) * step(reveal, 1.0);
        float tail = mix(1.0, smoothstep(0.0, 0.5, 1.0 - behind), trail);
        vec2 q = i.uv * _Tiling.xy - _Scroll.xy * age;
        float body = 0.5 + 0.5 * fx_fbm(vec3(q, seed * 7.0), 3);
        float streak = pow(fx_ridged(vec3(q * vec2(1.0, 3.0), seed * 3.0 + age * 0.4), 3), 5.0);
        float across = 1.0 - _Edges * pow(abs(i.uv.y * 2.0 - 1.0), 2.0);
        float e = body * across + streak * 0.45 + head * 0.6;
        float aa = fwidth(e) + 0.002;
        float alpha = smoothstep(_Erosion - aa, _Erosion + aa, e);
        vec3 ray = normalize(i.world - FX_CAMERA);
        float rim = 1.0 - abs(dot(normalize(i.normal), ray));
        float hot = clamp(head + streak * 1.5, 0.0, 1.0);
        vec3 light = mix(_Color.rgb, _Hot.rgb, hot) * (1.0 + _Rim * rim);
        float strength = alpha * tail * across * CG_OBJECT_CUSTOM1.y * _Color.a * FX_PRESENCE * fx_flicker(age, seed);
        fragColor = vec4(light * strength, 0.0);
        CG_GLOW(_Hot.rgb * hot * strength * _GlowGain);
    }
}

// Its glow (CG_GLOW) into the world's bloom.
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }
}
