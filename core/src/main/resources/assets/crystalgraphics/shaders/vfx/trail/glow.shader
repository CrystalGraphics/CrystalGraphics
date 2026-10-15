// A ki streak: a trail of energy, white-hot down its middle and its own colour out to soft edges (Effekseer's track,
// centre colour over side colours), its head rounded where it leaves the thing it follows, energy flowing back along it
// in streaks and eaten away toward its tail as it fades, tapering as it goes. _Wobble shakes its line across, more
// toward the tail: energy too wild to hold one. Added, so crossing streaks pile into light, and it blooms.
// On a trail slot (CgVfxFrame.trail): the layer's size is its half-width in blocks.
#type none
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Color      ("Its energy; its alpha a strength", color) = (0.25, 0.65, 1.8, 1)
    _Core       ("The white-hot middle", color) = (1.2, 1.25, 1.4, 1)
    _CoreWidth  ("The middle's share of the width", float) = 0.28
    _Softness   ("How soft its edges are: higher thins them", float) = 1.6
    _Tail       ("Its width at its tail, times its head's", float) = 0.15
    _TaperPower ("How late it tapers: 1 steadily, higher holds its width longer", float) = 0.8
    _Fade       ("How fast it fades along its life: higher sooner", float) = 1.4
    _Tiling     ("Noise cells a block along it, and across it", vec4) = (0.9, 1.3, 0, 0)
    _Flow       ("How fast its energy flows back from the head, blocks a second", float) = 7.0
    _Erode      ("How much its tail is eaten away, 0..1", float) = 0.75
    _Wobble     ("How far its line shakes across, times its width", float) = 0.0
    _WobbleRate ("How fast it shakes, a second", float) = 9.0
    _GlowGain   ("Its glow past white", float) = 1.6
    _Noise  ("Noise", sampler3D) = "cg_noise"
    _FxTrail ("Trail points", sampler2D) = "black"
}

// trail: life 0..1, across -1..1, blocks along, blocks back from the head; shape: half-width, intensity, alive
struct v2f { vec4 trail; vec3 shape; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        FxTrailVertex v = fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM2,
                CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz, FX_CAMERA);
        float u = v.uv.x;
        float taper = mix(1.0, _Tail, pow(u, _TaperPower));
        vec3 world = fx_trail_place(v, taper);
        if (_Wobble > 0.0 && v.alive > 0.0) {
            float shake = fx_noise(vec3(v.along * 1.7, FX_AGE * _WobbleRate, FX_SEED * 13.0))
                    + 0.5 * fx_noise(vec3(v.along * 4.3, FX_AGE * _WobbleRate * 2.1, FX_SEED * 7.0));
            world += v.span * (shake * _Wobble * (0.25 + u));
        }
        o.trail = vec4(u, v.side, v.along, v.fromHead);
        o.shape = vec3(length(v.span) * taper, v.extra.y, v.alive);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.shape.z < 0.5) discard;
        float u = i.trail.x, across = abs(i.trail.y), along = i.trail.z, fromHead = i.trail.w;
        float halfWidth = max(i.shape.x, 1.0e-3);
        // Rounded where it leaves its thing: within its first half-width the strip closes as a circle does.
        float cap = clamp(fromHead / halfWidth, 0.0, 1.0);
        float r = across / max(sqrt(1.0 - (1.0 - cap) * (1.0 - cap)), 1.0e-3);
        if (r >= 1.0) discard;
        float core = exp(-r * r / (_CoreWidth * _CoreWidth) * 2.0);
        float body = pow(1.0 - r, _Softness);
        vec3 q = vec3(along * _Tiling.x - FX_AGE * _Flow * _Tiling.x, i.trail.y * _Tiling.y, FX_SEED * 9.0);
        float n = 0.5 + 0.5 * fx_fbm(q, 3);
        float streak = pow(fx_ridged(vec3(q.x * 0.6, q.y * 2.2, FX_SEED * 5.0 + FX_AGE * 0.7), 3), 4.0);
        float e = body * (0.55 + 0.45 * n) + streak * 0.45 * (1.0 - r) - u * u * _Erode;
        float alpha = smoothstep(0.0, 0.3, e);
        float fade = pow(1.0 - u, _Fade);
        float strength = alpha * fade * i.shape.y * _Color.a * FX_PRESENCE * fx_flicker(FX_AGE, FX_SEED);
        float hot = clamp(core * 1.15 + streak * 0.5 - u * 0.6, 0.0, 1.0);
        vec3 light = mix(_Color.rgb * (0.65 + 0.55 * n), _Core.rgb, hot);
        fragColor = vec4(light * strength, 0.0);
        CG_GLOW((_Core.rgb * hot + _Color.rgb * body * 0.35) * strength * _GlowGain);
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
