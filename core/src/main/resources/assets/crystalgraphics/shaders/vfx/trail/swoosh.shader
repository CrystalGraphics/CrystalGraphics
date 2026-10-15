// A blade's swoosh: the sheet a swing sweeps between its base and its tip, as action games draw a sword's or a fist's
// arc. Brightest along the tip's edge, where it is newest, in a hot line; streaks of light running along the swing,
// thinning toward the base and eaten away toward the tail; faded where it is seen edge-on, so it never shows as a
// line. Added, and it blooms. On a blade trail (CgVfxTrail.blade): the layer's size is how far it reaches toward the
// tip, 1 to it.
#type none
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Color    ("Its sheet; its alpha a strength", color) = (0.35, 0.7, 1.8, 1)
    _Hot      ("Its leading edge and streaks", color) = (1.3, 1.35, 1.5, 1)
    _Base     ("How far out from the base it starts to show, 0..1", float) = 0.25
    _Edge     ("How tight its hot edge is: higher thinner", float) = 14.0
    _Decay    ("How fast it dims behind the swing: higher sooner", float) = 2.4
    _Tiling   ("Streak cells along the swing's life, and across from base to tip", vec4) = (3.5, 7.0, 0, 0)
    _Flow     ("How fast its streaks run along it, a second", float) = 2.5
    _Erode    ("How much its tail is eaten away, 0..1", float) = 0.85
    _GlowGain ("Its glow past white", float) = 1.8
    _Noise  ("Noise", sampler3D) = "cg_noise"
    _FxTrail ("Trail points", sampler2D) = "black"
}

// trail: life 0..1, base to tip 0..1, facing, intensity; alive
struct v2f { vec4 trail; float alive; };

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
        o.trail = vec4(v.uv.x, v.uv.y, fx_trail_facing(v, FX_CAMERA), v.extra.y);
        o.alive = v.alive;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(v.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.alive < 0.5) discard;
        float u = i.trail.x, outward = i.trail.y;
        float sheet = smoothstep(_Base, 1.0, outward);
        float edge = exp(-(1.0 - outward) * _Edge) * exp(-u * 4.0);
        vec3 q = vec3(u * _Tiling.x - FX_AGE * _Flow, outward * _Tiling.y, FX_SEED * 11.0);
        float streak = pow(fx_ridged(vec3(q.x, q.y, q.z), 3), 3.0);
        float n = 0.5 + 0.5 * fx_fbm(q * vec3(0.5, 1.0, 1.0), 3);
        float e = sheet * (0.5 + 0.5 * n) + streak * 0.6 * sheet + edge - u * u * _Erode;
        float alpha = smoothstep(0.0, 0.25, e);
        float behind = exp(-u * _Decay);
        // Edge-on, a sheet would draw as a bright line: it fades to nothing there.
        float facing = smoothstep(0.05, 0.4, i.trail.z);
        float strength = alpha * behind * facing * i.trail.w * _Color.a * FX_PRESENCE;
        float hot = clamp(edge * 1.5 + streak * 0.7 * (1.0 - u), 0.0, 1.0);
        vec3 light = mix(_Color.rgb * (0.6 + 0.6 * n), _Hot.rgb, hot);
        fragColor = vec4(light * strength, 0.0);
        CG_GLOW((_Hot.rgb * hot + _Color.rgb * sheet * 0.3) * strength * _GlowGain);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }
}
