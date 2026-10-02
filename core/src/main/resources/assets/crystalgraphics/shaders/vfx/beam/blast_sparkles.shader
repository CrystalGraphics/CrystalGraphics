// Glowing sparkles drifting out of the final blast: bright dots and streaks floating up and away, each white-hot in the
// middle in a soft bloom of its colour; the streaks stretched along the way they fly, the dots each squashed its own
// way, a few of them large, shimmering gently. Stateless CgVfxRibbons, one sparkle a ribbon (fx_fleck.glsl).
// CG_OBJECT_CUSTOM1: x the blast's radius in blocks, y which batch, z an intensity, w seconds since the blast. Colour A
// is the bloom, colour B the hot centre, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_fleck.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count   ("Sparkles drawn, each batch", float) = 90.0
    _Speed   ("Launch speed, blast radii a second", float) = 0.9
    _Drag    ("How fast that decays, a second", float) = 0.9
    _Gravity ("Pull downward, negative to rise, blast radii a second squared", float) = 0.15
    _Sway    ("Flutter each way as it falls, blast radii", float) = 0.03
    _Life    ("Seconds a sparkle lasts, on average", float) = 5.0
    _Size    ("Sparkle radius with its bloom, share of the blast radius", float) = 0.026
    _Streaks ("Share of sparkles drawn as streaks", float) = 0.4
}

struct v2f { vec3 world; vec3 spark; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x;
        vec2 corner = vec2(cg_TexCoord0.x * 2.0 - 1.0, cg_TexCoord0.y * 2.0 - 1.0);
        float scale = CG_OBJECT_CUSTOM1.x, since = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w;
        vec4 h = fx_hash41(index * 1.71 + seed * 29.0 + CG_OBJECT_CUSTOM1.y * 5.13);
        vec4 k = fx_hash41(index * 3.37 + seed * 11.0 + CG_OBJECT_CUSTOM1.y * 2.71);
        float t = since - 0.4 * k.x;
        float life = _Life * (0.6 + 0.8 * k.y);
        bool alive = index < _Count && t > 0.0 && t < life && CG_OBJECT_CUSTOM1.z > 0.0;
        vec3 offset = fx_fleck_flight(h, max(t, 0.0), _Speed, _Drag, _Gravity, 0.7);
        // Fluttering a little as it falls, once its throw is spent.
        float settle = smoothstep(0.2, 1.2, t);
        offset += _Sway * settle * vec3(sin(t * (0.9 + 0.8 * k.y) + k.w * 6.28318531),
                                        0.6 * sin(t * (0.7 + 0.6 * k.x) + k.z * 6.28318531),
                                        cos(t * (0.8 + 0.7 * k.z) + k.y * 6.28318531));
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        // Mostly small, a few large; a streak drawn out along its flight, a dot squashed its own way.
        vec4 m = fx_hash41(index * 5.29 + seed * 13.0 + CG_OBJECT_CUSTOM1.y * 4.07);
        float size = alive ? _Size * scale * (0.4 + 2.0 * k.z * k.z) : 0.0;
        bool streak = m.x < _Streaks;
        vec2 extent = streak ? vec2(size * (1.8 + 2.2 * m.y), size * 0.45) : vec2(size, size * mix(0.55, 1.0, m.y));
        float angle = streak ? fx_fleck_heading(h, max(t, 0.0), _Speed, _Drag, _Gravity, 0.7, right, up) : m.z * 6.28318531;
        vec3 world = fx_fleck_corner(CG_OBJECT_TO_WORLD[3].xyz + offset * scale, corner, extent, angle, right, up);
        // A gentle shimmer, fading in and out.
        float twinkle = 0.85 + 0.15 * sin(t * (6.0 + 6.0 * k.w) + k.z * 6.28318531);
        float fade = smoothstep(0.0, 0.08, t) * (1.0 - smoothstep(0.6, 1.0, t / life));
        o.world = world;
        o.spark = vec3(corner, twinkle * fade);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 q = i.spark.xy;
        float r = length(q);
        float aa = fwidth(r) + 1.0e-3;
        // A round disc, white-hot in the middle, in a wide soft bloom.
        float disc = 1.0 - smoothstep(0.26 - aa, 0.26 + aa, r);
        float bloom = exp(-r * r * 9.0) * (1.0 - smoothstep(0.7, 1.0, r));
        vec3 col = mix(CG_OBJECT_CUSTOM2.rgb, CG_OBJECT_CUSTOM3.rgb, exp(-r * r * 60.0)) * disc * 1.8
                 + CG_OBJECT_CUSTOM2.rgb * bloom * 0.9;
        fragColor = vec4(col * i.spark.z * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z, 1.0);
    }
}
