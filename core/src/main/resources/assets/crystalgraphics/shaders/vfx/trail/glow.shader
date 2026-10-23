// A ki streak: a strip of energy, white-hot down its middle and its own colour out to soft edges (Effekseer's track,
// centre colour over side colours), energy flowing back along it in streaks and eaten away toward its tail as it fades.
// _Wobble shakes its line across, more toward the tail: energy too wild to hold one. Added, so crossing streaks pile
// into light, and it blooms. A ribbon's material: a Cascade trail behind a moving thing, or a ribbon through particles;
// its renderer tiles UV1 once a block (fx_strip.glsl), and its emitter's size over life tapers it.
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_strip.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

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

// trail: life 0..1, across -1..1, blocks along; tint: its particles' colour and opacity
struct v2f { vec3 trail; vec4 tint; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.trail = fx_strip(v.texCoords[0], v.texCoords[1], p.relativeTime);
        vec3 world = v.worldPosition;
        if (_Wobble > 0.0) {
            float shake = fx_noise(vec3(o.trail.z * 1.7, FX_AGE * _WobbleRate, FX_SEED * 13.0))
                    + 0.5 * fx_noise(vec3(o.trail.z * 4.3, FX_AGE * _WobbleRate * 2.1, FX_SEED * 7.0));
            world += v.tangentToWorld[0] * (p.positionAndSize.w * shake * _Wobble * (0.25 + o.trail.x));
        }
        o.tint = p.color;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        FxGlow g = FxGlow(_Color.rgb, _Core.rgb, _CoreWidth, _Softness, _Fade, _Tiling.xy, _Flow, _Erode);
        vec3 glow;
        vec3 light = fx_strip_glow(g, _Noise, i.trail, FX_AGE, FX_SEED, glow);
        if (dot(light, light) == 0.0) discard;
        float strength = i.tint.a * _Color.a * FX_PRESENCE;
        fragColor = vec4(light * i.tint.rgb * strength, 0.0);
        CG_GLOW(glow * i.tint.rgb * strength * _GlowGain);
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
