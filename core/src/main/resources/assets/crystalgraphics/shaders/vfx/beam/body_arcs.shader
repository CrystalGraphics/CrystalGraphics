// Lightning crawling along an energy wave's body: bolts between two points of its surface, each a run along the body that
// winds partway round it, bowed off it and jagged at every scale, forking into branches that strike back onto it; some
// leap far off the body before coming down. A strike holds its channel and strobes through its return strokes. Stateless
// ribbons that read the path itself (CgVfxFrame.pathRibbons, fx_tube.glsl's fx_ring_at, fx_lightning.glsl): three per
// bolt, its channel and two forks. Colour A is the glow, colour B the white core, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_lightning.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _FxPath ("Path rings", sampler2D) = "black"
    _Count  ("Bolts at most", float) = 24.0
    _Rate   ("New strikes a second, each bolt", float) = 6.0
    _Chance ("Share of moments a bolt strikes", float) = 0.7
    _Length ("Bolt length along the body, blocks", float) = 3.4
    _Lift   ("How far a bolt bows off the surface, share of the radius", float) = 0.25
    _Jag    ("Jaggedness, share of the bolt's length", float) = 0.45
    _Leap   ("Share of bolts that leap off the body", float) = 0.55
    _Reach  ("How far a leaping bolt bows out, share of the radius", float) = 2.2
    _Width  ("Half-width with its glow, blocks", float) = 0.14
    _Core   ("White core, share of the half-width", float) = 0.035
}

struct v2f { vec3 world; vec2 bolt; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // A point on the body at arc length s, wound to angle a, lift radii off its surface, moved off blocks across it.
    vec3 onBody(int row, vec4 header, float s, float a, float lift, vec2 off) {
        FxRing r = fx_ring_at(_FxPath, row, s, header);
        vec3 radial = cos(a) * r.normal + sin(a) * cross(r.tangent, r.normal);
        vec3 lateral = cross(r.tangent, radial);
        return r.position + radial * (r.radius * (1.0 + lift) + off.x * 0.6) + lateral * off.y;
    }

    // A run at t: from arc length s0 over span blocks, from angle a0 winding by wind, bowed lift radii off the body.
    vec3 run(int row, vec4 header, float t, float s0, float span, float a0, float wind, float lift, float seed) {
        vec2 off = fx_bolt_offset(t, seed) * _Jag * span;
        return onBody(row, header, s0 + t * span, a0 + wind * t, lift * sin(3.14159265 * t), off);
    }

    // Ribbon `part` of a bolt at t: 0 its channel, 1 and 2 forks leaving it at fork.x, fork.y of its length, turning by
    // fork.z radians round the body.
    vec3 strand(int row, vec4 header, float part, float t, vec4 bolt, float lift, float seed, vec3 fork) {
        if (part < 0.5) return run(row, header, t, bolt.x, bolt.y, bolt.z, bolt.w, lift, seed);
        float s = bolt.x + fork.x * bolt.y, a = bolt.z + bolt.w * fork.x;
        vec3 start = run(row, header, fork.x, bolt.x, bolt.y, bolt.z, bolt.w, lift, seed);
        vec3 base = onBody(row, header, s, a, 0.0, vec2(0.0));
        return run(row, header, t, s, fork.y * bolt.y, a, fork.z, lift * 0.4, seed + part * 41.3) + (start - base) * (1.0 - t);
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x, along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
        int row = int(CG_OBJECT_CUSTOM0.x + 0.5);
        vec4 header = fx_path_header(_FxPath, row);
        float pathLength = header.y, seed = header.z, age = header.w;
        float boltIndex = floor(index / 3.0), part = index - boltIndex * 3.0;
        vec4 h = fx_hash41(boltIndex * 1.41 + seed * 83.0);
        float clock = age * _Rate * (0.75 + 0.5 * h.x) + h.y;
        float epoch = floor(clock), moment = fract(clock);
        vec4 e = fx_hash41(boltIndex * 2.33 + epoch * 0.77 + seed * 37.0);
        float span = _Length * (0.6 + 0.8 * e.x);
        // arc length it starts at, its span, the angle it starts at, how far it winds round
        vec4 bolt = vec4(e.y * max(pathLength - span, 0.0), span, e.z * 6.28318531, (e.w - 0.5) * 2.4);
        // A leaping bolt bows far out from the body, a crackling bridge between two points on it.
        float lift = _Lift * (0.6 + 0.8 * e.x) + (fract(e.w * 29.7) < _Leap ? _Reach * (0.5 + e.x) : 0.0);
        float boltSeed = boltIndex * 13.1 + epoch * 7.7 + seed * 3.0;
        vec4 f = fx_hash41(boltSeed + part * 5.9);
        vec3 fork = vec3(0.2 + 0.5 * f.x, 0.3 + 0.35 * f.y, (f.z - 0.5) * 2.2);
        bool present = part < 0.5 || f.w < (part < 1.5 ? 0.75 : 0.4);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        vec3 p = origin + strand(row, header, part, along, bolt, lift, boltSeed, fork);
        vec3 ahead = strand(row, header, part, min(along + 1.0 / 32.0, 1.0), bolt, lift, boltSeed, fork);
        vec3 behind = strand(row, header, part, max(along - 1.0 / 32.0, 0.0), bolt, lift, boltSeed, fork);
        bool drawn = boltIndex < _Count && fract(e.w * 17.3) < _Chance && pathLength > span && present;
        float taper = part < 0.5 ? 1.0 : 1.0 - 0.75 * along;
        vec3 world = fx_ribbon_vertex(p, ahead - behind, FX_CAMERA, drawn ? _Width * taper : 0.0, side);
        float ends = part < 0.5 ? smoothstep(0.0, 0.05, along) * smoothstep(1.0, 0.95, along)
                                : 0.6 * (1.0 - smoothstep(0.6, 1.0, along));
        o.world = world;
        o.bolt = vec2(side, fx_bolt_strobe(moment, boltSeed) * ends);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 l = fx_bolt_profile(i.bolt.x, fwidth(i.bolt.x), _Core);
        vec3 col = CG_OBJECT_CUSTOM3.rgb * l.x * 2.6 + CG_OBJECT_CUSTOM2.rgb * (l.y * 0.55 + l.z * 0.3);
        fragColor = vec4(col * i.bolt.y * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.w, 1.0);
    }
}
