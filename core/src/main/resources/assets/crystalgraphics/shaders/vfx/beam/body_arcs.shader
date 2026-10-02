// Lightning crawling along an energy wave's body: jagged arcs riding just off its surface, each a short run along the
// body that winds partway round it, kinked at every segment, re-rolled several times a second. Stateless ribbons that
// read the path itself (CgVfxFrame.pathRibbons, fx_tube.glsl's fx_ring_at). Colour A is the glow round an arc, colour B
// its white line, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _FxPath ("Path rings", sampler2D) = "black"
    _Count  ("Arcs at most", float) = 10.0
    _Rate   ("New arcs a second, each", float) = 9.0
    _Chance ("Share of moments an arc shows", float) = 0.6
    _Length ("Arc length along the body, blocks", float) = 2.4
    _Lift   ("Height off the surface, share of the radius", float) = 0.12
    _Jag    ("Kink, share of the radius", float) = 0.22
    _Width  ("Half-width with its glow, blocks", float) = 0.05
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

    // The arc's point at t along it, in the effect's space: on the body at arc length s, wound round to angle a.
    vec3 crawl(int row, vec4 header, float s, float a, float t, float epoch, float index) {
        FxRing r = fx_ring_at(_FxPath, row, s, header);
        vec3 binormal = cross(r.tangent, r.normal);
        vec4 k = fx_hash41(index * 7.1 + epoch * 1.3 + floor(t * 8.0 + 0.5) * 2.9);
        float bulge = sin(3.14159265 * t);
        float lift = r.radius * (1.0 + _Lift * (0.5 + bulge) + _Jag * (k.x - 0.5) * 2.0 * bulge);
        a += (k.y - 0.5) * 0.6 * bulge;
        return r.position + lift * (cos(a) * r.normal + sin(a) * binormal) + r.tangent * (k.z - 0.5) * _Jag * r.radius * bulge;
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x, along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
        int row = int(CG_OBJECT_CUSTOM0.x + 0.5);
        vec4 header = fx_path_header(_FxPath, row);
        float pathLength = header.y, seed = header.z, age = header.w;
        vec4 h = fx_hash41(index * 1.41 + seed * 83.0);
        float clock = age * _Rate * (0.75 + 0.5 * h.x) + h.y;
        float epoch = floor(clock), moment = fract(clock);
        vec4 e = fx_hash41(index * 2.33 + epoch * 0.77 + seed * 37.0);
        float span = _Length * (0.6 + 0.8 * e.x);
        float s0 = e.y * max(pathLength - span, 0.0);
        float a0 = e.z * 6.28318531, wind = (e.w - 0.5) * 2.4;
        float s = s0 + along * span;
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        vec3 p = origin + crawl(row, header, s, a0 + wind * along, along, epoch, index);
        vec3 ahead = origin + crawl(row, header, min(s + 0.05, pathLength), a0 + wind * min(along + 0.02, 1.0), along, epoch, index);
        bool drawn = index < _Count && fract(e.w * 17.3) < _Chance && pathLength > span;
        vec3 world = fx_ribbon_vertex(p, ahead - p, FX_CAMERA, drawn ? _Width : 0.0, side);
        o.world = world;
        o.arc = vec3(along, side, (1.0 - moment) * (1.0 - moment));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float across = abs(i.arc.y);
        float aa = fwidth(across) + 1.0e-3;
        float glow = exp(-across * across * 5.0) * 0.7;
        float line = 1.0 - smoothstep(0.1, 0.1 + 2.0 * aa, across);
        float ends = smoothstep(0.0, 0.1, i.arc.x) * smoothstep(1.0, 0.9, i.arc.x);
        vec3 col = CG_OBJECT_CUSTOM2.rgb * glow + CG_OBJECT_CUSTOM3.rgb * line * 1.8;
        fragColor = vec4(col * ends * i.arc.z * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.w, 1.0);
    }
}
