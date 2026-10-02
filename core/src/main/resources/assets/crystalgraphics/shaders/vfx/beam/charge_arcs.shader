// Electric arcs crackling over a charging orb: each a jagged bolt from one point on its bright core to another, kinked at
// every segment, flaring for a moment and re-rolled several times a second. Stateless (CgVfxRibbons), in a unit space
// where the orb's radius is 1; the bolts hug the core, which the plasma shows at about two thirds of it. CG_OBJECT_CUSTOM1: x the orb's radius in blocks, z an intensity. Colour A is the glow
// round a bolt, colour B its white line, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count   ("Arcs at most", float) = 7.0
    _Rate    ("New arcs a second, each", float) = 11.0
    _Chance  ("Share of moments an arc shows", float) = 0.5
    _Span    ("Angle an arc spans, radians", float) = 0.8
    _Jag     ("Kink, share of the orb's radius", float) = 0.1
    _Width   ("Half-width with its glow, share of the orb's radius", float) = 0.09
}

struct v2f { vec3 world; vec3 arc; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // The bolt's point at t from a to b over the surface, lifted off it in the middle and kinked at each segment.
    vec3 bolt(vec3 a, vec3 b, float t, float epoch, float index) {
        vec3 p = normalize(mix(a, b, t));
        float bulge = sin(3.14159265 * t);
        vec4 k = fx_hash41(index * 5.3 + epoch * 1.7 + floor(t * 8.0 + 0.5) * 3.9);
        vec3 kink = (k.xyz - 0.5) * 2.0 * _Jag * bulge;
        return p * (0.62 + 0.16 * bulge * k.w) + kink;
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x, along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        vec4 h = fx_hash41(index * 1.31 + seed * 97.0);
        float clock = age * _Rate * (0.75 + 0.5 * h.x) + h.y;
        float epoch = floor(clock), moment = fract(clock);
        vec4 e = fx_hash41(index * 2.71 + epoch * 0.913 + seed * 41.0);
        vec3 a = fx_sphere_dir(e.xy);
        vec3 axis = normalize(cross(a, fx_sphere_dir(e.zw) + vec3(1.0e-3)));
        float span = _Span * (0.6 + 0.8 * e.w);
        vec3 b = normalize(a * cos(span) + cross(axis, a) * sin(span));
        vec3 p = bolt(a, b, along, epoch, index);
        vec3 ahead = bolt(a, b, min(along + 0.02, 1.0), epoch, index);
        vec3 behind = bolt(a, b, max(along - 0.02, 0.0), epoch, index);
        vec3 world = (CG_OBJECT_TO_WORLD * vec4(p, 1.0)).xyz;
        vec3 tangent = mat3(CG_OBJECT_TO_WORLD) * (ahead - behind);
        bool drawn = index < _Count && fract(e.z * 13.7) < _Chance;
        float halfWidth = drawn ? CG_OBJECT_CUSTOM1.x * _Width : 0.0;
        world = fx_ribbon_vertex(world, tangent, FX_CAMERA, halfWidth, side);
        float flare = (1.0 - moment) * (1.0 - moment);
        o.world = world;
        o.arc = vec3(along, side, flare);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float across = abs(i.arc.y);
        float aa = fwidth(across) + 1.0e-3;
        float glow = exp(-across * across * 5.0) * 0.8;
        float line = 1.0 - smoothstep(0.09, 0.09 + 2.0 * aa, across);
        float ends = smoothstep(0.0, 0.12, i.arc.x) * smoothstep(1.0, 0.88, i.arc.x);
        vec3 col = CG_OBJECT_CUSTOM2.rgb * glow * 0.6 + CG_OBJECT_CUSTOM3.rgb * line * 1.8;
        fragColor = vec4(col * ends * i.arc.z * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z, 1.0);
    }
}
