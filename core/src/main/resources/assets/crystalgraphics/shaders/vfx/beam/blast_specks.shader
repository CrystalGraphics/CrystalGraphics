// Dark debris specks thrown out of the final blast: small chunks, slivers and shards flung up and out, tumbling, slowing
// and falling, each its own size, stretch and raggedness. Stateless CgVfxRibbons, one speck a ribbon (fx_fleck.glsl).
// CG_OBJECT_CUSTOM1: x the blast's radius in blocks, y which batch (each draws a different set), z an intensity, w
// seconds since the blast. Colour A is the specks. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_fleck.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count   ("Specks drawn, each batch", float) = 96.0
    _Speed   ("Launch speed, blast radii a second", float) = 1.1
    _Drag    ("How fast that decays, a second", float) = 0.8
    _Gravity ("Pull downward, blast radii a second squared", float) = 0.9
    _Life    ("Seconds a speck lasts, on average", float) = 4.5
    _Size    ("Speck radius, share of the blast radius", float) = 0.008
}

struct v2f { vec3 world; vec4 speck; float fade; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x;
        vec2 corner = vec2(cg_TexCoord0.x * 2.0 - 1.0, cg_TexCoord0.y * 2.0 - 1.0);
        float scale = CG_OBJECT_CUSTOM1.x, since = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w;
        vec4 h = fx_hash41(index * 1.37 + seed * 53.0 + CG_OBJECT_CUSTOM1.y * 7.31);
        vec4 k = fx_hash41(index * 2.91 + seed * 17.0 + CG_OBJECT_CUSTOM1.y * 3.17);
        float t = since - 0.15 * k.x;
        float life = _Life * (0.6 + 0.8 * k.y);
        bool alive = index < _Count && t > 0.0 && t < life && CG_OBJECT_CUSTOM1.z > 0.0;
        vec3 offset = fx_fleck_flight(h, max(t, 0.0), _Speed, _Drag, _Gravity, 0.55);
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        // Mostly small, a few large; stretched unevenly, from chunks to slivers; shrinking away at the end.
        vec4 m = fx_hash41(index * 4.13 + seed * 7.0 + CG_OBJECT_CUSTOM1.y * 1.91);
        float size = alive ? _Size * scale * (0.4 + 2.4 * k.z * k.z) * (1.0 - smoothstep(0.75, 1.0, t / life)) : 0.0;
        vec2 extent = vec2(size, size * mix(0.25, 1.0, m.x * m.x));
        float angle = k.w * 6.28318531 + t * (k.x - 0.5) * 12.0;
        vec3 world = fx_fleck_corner(CG_OBJECT_TO_WORLD[3].xyz + offset * scale, corner, extent, angle, right, up);
        o.world = world;
        // where on it, its shape seed, how spiky its edge is
        o.speck = vec4(corner, k.w * 40.0, mix(1.2, 4.0, m.y));
        o.fade = smoothstep(0.0, 0.05, t) * (1.0 - smoothstep(0.55, 1.0, t / life));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 q = i.speck.xy;
        float r = length(q);
        // A ragged chunk, not a dot: its edge wanders with the angle, lumpy at low frequency and spiky at high.
        vec2 around = q / max(r, 1.0e-4);
        float edge = 0.5 + 0.48 * fx_value_noise(vec3(around * i.speck.w, i.speck.z));
        float aa = fwidth(r) + 1.0e-3;
        float alpha = (1.0 - smoothstep(edge - aa, edge + aa, r)) * i.fade * CG_OBJECT_CUSTOM1.z * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
