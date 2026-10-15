// An anime streak: flat bands across a trail, as cel animation paints ki (white core, a bright band, the colour at its
// edge), each band narrowing toward the tail so the outer ones run out first, the tail breaking into hard brushed
// streaks rather than fading, an optional ink edge. The banded toon look (toon/banded.shader) on a trail.
// On a trail slot (CgVfxFrame.trail): the layer's size is its half-width in blocks. _Bands binds the look's `bands`.
#type none
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Bands    ("Its core, its middle band and its edge", color[3]) = (1, 1, 1, 1), (0.55, 0.9, 1.6, 1), (0.1, 0.45, 1.5, 1)
    _Widths   ("Where the core and the middle band end across it, 0..1", vec4) = (0.32, 0.64, 0, 0)
    _Ink      ("Its ink edge; alpha 0 for none", color) = (0.02, 0.03, 0.08, 0)
    _InkWidth ("The ink's share of the width", float) = 0.12
    _Tail     ("Its width at its tail, times its head's", float) = 0.25
    _Break    ("How far up from its tail it breaks into streaks, 0..1", float) = 0.55
    _Streaks  ("Streaks across it", float) = 5.0
    _GlowGain ("Its core's glow past white", float) = 1.2
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
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
        float taper = mix(1.0, _Tail, u);
        o.trail = vec4(u, v.side, v.along, v.fromHead);
        o.shape = vec3(length(v.span) * taper, v.extra.y, v.alive);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(fx_trail_place(v, taper), 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.shape.z < 0.5) discard;
        float u = i.trail.x, across = abs(i.trail.y), fromHead = i.trail.w;
        float halfWidth = max(i.shape.x, 1.0e-3);
        float cap = clamp(fromHead / halfWidth, 0.0, 1.0);
        float r = across / max(sqrt(1.0 - (1.0 - cap) * (1.0 - cap)), 1.0e-3);
        // Its tail breaks into streaks: each strand across it runs out at its own point along it.
        float strand = fx_value_noise(vec3(floor(i.trail.y * _Streaks * 0.5 + 0.5 * _Streaks) * 1.7, FX_SEED * 31.0, 0.5));
        float runsOut = 1.0 - _Break * (0.35 + 0.65 * strand);
        float aa = fwidth(r) + 1.0e-3;
        float shape = 1.0 - smoothstep(1.0 - aa, 1.0, r);
        float uaa = fwidth(u) + 1.0e-3;
        float broken = u < 1.0 - _Break ? 1.0 : 1.0 - smoothstep(runsOut - uaa, runsOut + uaa, u);
        float cover = shape * broken;
        if (cover <= 0.0) discard;
        // Each band narrows toward the tail, the outer ones first.
        float w0 = _Widths.x * (1.0 - 0.5 * u), w1 = mix(_Widths.y, _Widths.x, u * 0.8);
        float inCore = 1.0 - smoothstep(w0 - aa, w0 + aa, r);
        float inMiddle = 1.0 - smoothstep(w1 - aa, w1 + aa, r);
        vec3 colour = mix(mix(_Bands(2).rgb, _Bands(1).rgb, inMiddle), _Bands(0).rgb, inCore);
        float ink = _Ink.a * smoothstep(1.0 - _InkWidth - aa, 1.0 - _InkWidth + aa, r);
        colour = mix(colour, _Ink.rgb, ink);
        float alpha = cover * i.shape.y * FX_PRESENCE;
        fragColor = vec4(colour * alpha, alpha);
        CG_GLOW(_Bands(0).rgb * inCore * (1.0 - ink) * alpha * _GlowGain);
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
