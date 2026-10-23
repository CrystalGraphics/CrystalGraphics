// A blade's swoosh: the sheet a swing sweeps between its base and its tip, as action games draw a sword's or a fist's
// arc. Brightest along the tip's edge, where it is newest, in a hot line; streaks of light running along the swing,
// thinning toward the base and eaten away toward the tail; faded where it is seen edge-on, so it never shows as a
// line. Added, and it blooms. An anim trail's material: its edge's first point the base, its second the tip.
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_strip.glsl"
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
}

// trail: life 0..1, base to tip 0..1, how squarely it faces the eye, its samples' opacity
struct v2f { vec4 trail; };

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
        // The world position is from the eye, so the eye is the origin.
        float facing = abs(dot(normalize(v.tangentToWorld[2]), normalize(-v.worldPosition + vec3(1.0e-6))));
        o.trail = vec4(p.relativeTime, v.texCoords[0].y, facing, p.color.a);
        gl_Position = cg_material_project(v);
    }

    void fragment(in v2f i, out vec4 fragColor) {
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
