// Ink streaks sweeping out of the final blast, as anime draws an explosion's shock: long dark tapered strokes, each an
// arc of a circle round the blast in the eye's plane, flying outward as they thin and vanish within a moment. Stateless
// CgVfxRibbons, one stroke a ribbon. CG_OBJECT_CUSTOM1: x the blast's radius in blocks, z an intensity, w seconds since
// the blast. Colour A is the ink. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count  ("Strokes", float) = 20.0
    _Life   ("Seconds a stroke lasts", float) = 1.0
    _Spread ("Seconds over which strokes start", float) = 0.8
    _Reach  ("How far a stroke flies out, blast radii", float) = 1.6
    _Span   ("Arc a stroke spans, radians", float) = 0.9
    _Width  ("Half-width at its thickest, share of the blast radius", float) = 0.035
}

struct v2f { vec3 world; vec2 stroke; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x, along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
        float scale = CG_OBJECT_CUSTOM1.x, since = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w;
        vec4 h = fx_hash41(index * 1.93 + seed * 41.0);
        float t = since - _Spread * h.x;
        float life = _Life * (0.7 + 0.6 * h.y);
        float u = clamp(t / life, 0.0, 1.0);
        bool alive = index < _Count && t > 0.0 && t < life && CG_OBJECT_CUSTOM1.z > 0.0;
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        // An arc round the blast, flying out fast and easing; mostly in the upper half, where an explosion throws.
        float radius = scale * (0.7 + _Reach * (1.0 - (1.0 - u) * (1.0 - u)) * (0.7 + 0.6 * h.z));
        float centreAngle = mix(-0.5, 3.64, h.w);
        float a = centreAngle + _Span * (0.6 + 0.8 * h.z) * (along - 0.5);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 p = centre + (right * cos(a) + up * sin(a)) * radius;
        vec3 tangent = right * -sin(a) + up * cos(a);
        // Tapered to points at both ends, thinning away as it ends.
        float taper = pow(sin(3.14159265 * along), 0.8) * (1.0 - u * u);
        float width = alive ? _Width * scale * (0.6 + 0.8 * h.y) * taper : 0.0;
        vec3 world = fx_ribbon_vertex(p, tangent, FX_CAMERA, width, side);
        o.world = world;
        o.stroke = vec2(side, 1.0);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float across = abs(i.stroke.x);
        float aa = fwidth(across) + 1.0e-3;
        float alpha = (1.0 - smoothstep(1.0 - 2.0 * aa, 1.0, across)) * CG_OBJECT_CUSTOM1.z * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
