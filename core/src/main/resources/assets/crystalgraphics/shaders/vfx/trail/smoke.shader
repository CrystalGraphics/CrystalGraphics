// A smoke trail: what a meteor, a falling fireball or a ki blast through dust leaves behind. Thin and dense at its
// head, billowing wider and thinner as it ages, its edge torn by rolling noise that drifts up and outward, lit by the
// world's light and the sun with a darker underside, hot at its head where _Glow says (a fire's own smoke).
// Blended over what is behind it. On a trail slot (CgVfxFrame.trail): the layer's size is its half-width at its head.
#type none
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Color    ("The smoke; its alpha its density", color) = (0.42, 0.4, 0.38, 0.85)
    _Shadow   ("Its underside and thick middle", color) = (0.16, 0.15, 0.15, 1)
    _Glow     ("The glow at its head; alpha 0 for none", color) = (1.6, 0.6, 0.15, 0)
    _Spread   ("Its width at its life's end, times its head's", float) = 3.5
    _Scale    ("Billows a block", float) = 0.9
    _Rise     ("How fast it drifts up as it ages, blocks a second", float) = 0.8
    _Fade     ("How fast it thins along its life", float) = 1.1
    _Noise  ("Noise", sampler3D) = "cg_noise"
    _FxTrail ("Trail points", sampler2D) = "black"
}

struct v2f { vec4 trail; vec3 shape; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        FxTrailVertex v = fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM2,
                CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz, FX_CAMERA);
        float u = v.uv.x;
        // Billowing out as it ages, eased so its head stays tight.
        float spread = mix(1.0, _Spread, 1.0 - (1.0 - u) * (1.0 - u));
        vec3 world = fx_trail_place(v, spread) + vec3(0.0, _Rise * v.age, 0.0);
        o.trail = vec4(u, v.side, v.along, v.age);
        // How far up this edge is, -1 to 1: its underside is in shadow.
        o.shape = vec3(v.extra.y, v.alive, v.alive > 0.0 ? normalize(v.span).y * v.reach : 0.0);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.shape.y < 0.5) discard;
        float u = i.trail.x, across = i.trail.y, age = i.trail.w;
        vec3 p = vec3(i.trail.z * _Scale, across * 1.4 - age * 0.6, FX_SEED * 5.0) + vec3(0.0, 0.0, age * 0.35);
        float billow = 0.5 + 0.5 * fx_fbm(p, 4);
        float detail = 0.5 + 0.5 * fx_fbm(p * 2.7 + 3.1, 3);
        float edge = 1.0 - across * across;
        float density = edge * mix(billow, detail, 0.35) * 1.6 - 0.35 - u * 0.5;
        float aa = fwidth(density) + 1.0e-3;
        float alpha = smoothstep(-aa, 0.35 + aa, density) * pow(1.0 - u, _Fade) * _Color.a * i.shape.x * FX_PRESENCE;
        if (alpha < 0.003) discard;
        // Lit from above and by the sun, darker where it is thick and underneath.
        float top = 0.5 + 0.5 * i.shape.z;
        float sun = 0.55 + 0.45 * max(CG_SUN_DIRECTION.y, 0.0) * CG_DAYLIGHT;
        vec3 colour = mix(_Shadow.rgb, _Color.rgb, clamp(0.35 + 0.5 * billow * top + 0.25 * detail, 0.0, 1.0)) * sun;
        float hot = _Glow.a * exp(-u * 7.0) * edge;
        fragColor = vec4(colour * alpha, alpha);
        fragColor.rgb += _Glow.rgb * hot * alpha;
        CG_GLOW(_Glow.rgb * hot * alpha);
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
