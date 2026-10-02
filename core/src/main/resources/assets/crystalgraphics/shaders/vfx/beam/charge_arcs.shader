// Lightning crackling over a charging orb: bolts jumping between two points of its bright core, bowed off it and jagged
// at every scale, each forking once or twice into branches that strike back down into it. A strike holds its channel and
// strobes through its return strokes, several new strikes a second. Stateless (CgVfxRibbons, fx_lightning.glsl): three
// ribbons per bolt, its channel and two forks, in a unit space where the orb's radius is 1 and the core shows at about
// two thirds of it. CG_OBJECT_CUSTOM1.x is the orb's radius in blocks, .z an intensity. Colour A is the glow, colour B
// the white core, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_lightning.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count  ("Bolts at most", float) = 16.0
    _Rate   ("New strikes a second, each bolt", float) = 6.0
    _Chance ("Share of moments a bolt strikes", float) = 0.7
    _Span   ("Angle a bolt spans, radians", float) = 1.3
    _Lift   ("How far a bolt bows off the core, share of the radius", float) = 0.2
    _Jag    ("Jaggedness, share of the bolt's length", float) = 0.55
    _Leap   ("Share of bolts that leap out past the orb", float) = 0.35
    _Width  ("Half-width with its glow, share of the orb's radius", float) = 0.16
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

    // A channel at t from a to b over the core (unit vectors), bowed lift off it, jagged in the plane across it.
    vec3 channel(vec3 a, vec3 b, float t, float lift, float seed) {
        vec3 base = normalize(mix(a, b, t)) * (0.62 + lift * sin(3.14159265 * t));
        vec3 up = normalize(base);
        vec3 side = normalize(cross(b - a, up) + vec3(1.0e-5));
        vec2 off = fx_bolt_offset(t, seed) * _Jag * length(b - a);
        return base + up * off.x * 0.6 + side * off.y;
    }

    // Ribbon `part` of a bolt at t: 0 its channel, 1 and 2 forks leaving it at fork.x, fork.y long, veering to side fork.z.
    vec3 strand(float part, float t, vec3 a, vec3 b, float lift, float seed, vec3 fork) {
        if (part < 0.5) return channel(a, b, t, lift, seed);
        vec3 from = normalize(mix(a, b, fork.x));
        vec3 ahead = normalize(mix(a, b, min(fork.x + fork.y, 1.0)));
        vec3 veer = normalize(cross(b - a, ahead) + vec3(1.0e-5));
        vec3 to = normalize(ahead + veer * fork.z * fork.y * 1.6);
        vec3 start = channel(a, b, fork.x, lift, seed);
        return channel(from, to, t, lift * 0.4, seed + part * 41.3) + (start - from * 0.62) * (1.0 - t);
    }

    void vertex(out v2f o) {
        float index = cg_Normal.x, along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        float bolt = floor(index / 3.0), part = index - bolt * 3.0;
        vec4 h = fx_hash41(bolt * 1.31 + seed * 97.0);
        float clock = age * _Rate * (0.75 + 0.5 * h.x) + h.y;
        float epoch = floor(clock), moment = fract(clock);
        vec4 e = fx_hash41(bolt * 2.71 + epoch * 0.913 + seed * 41.0);
        vec3 a = fx_sphere_dir(e.xy);
        vec3 axis = normalize(cross(a, fx_sphere_dir(e.zw) + vec3(1.0e-3)));
        float span = _Span * (0.6 + 0.8 * e.w);
        vec3 b = normalize(a * cos(span) + cross(axis, a) * sin(span));
        // A leaping bolt bows far out past the skin, crackling into the air around the orb.
        float lift = _Lift * (0.6 + 0.8 * e.z) + (fract(e.x * 23.1) < _Leap ? 0.35 + 0.4 * e.y : 0.0);
        float boltSeed = bolt * 13.1 + epoch * 7.7 + seed * 3.0;
        vec4 f = fx_hash41(boltSeed + part * 5.9);
        vec3 fork = vec3(0.2 + 0.5 * f.x, 0.25 + 0.35 * f.y, f.z < 0.5 ? -1.0 : 1.0);
        bool present = part < 0.5 || f.w < (part < 1.5 ? 0.75 : 0.4);
        vec3 p = strand(part, along, a, b, lift, boltSeed, fork);
        vec3 ahead = strand(part, min(along + 1.0 / 32.0, 1.0), a, b, lift, boltSeed, fork);
        vec3 behind = strand(part, max(along - 1.0 / 32.0, 0.0), a, b, lift, boltSeed, fork);
        vec3 world = (CG_OBJECT_TO_WORLD * vec4(p, 1.0)).xyz;
        vec3 tangent = mat3(CG_OBJECT_TO_WORLD) * (ahead - behind);
        bool drawn = bolt < _Count && fract(e.z * 13.7) < _Chance && present;
        // A channel fades in at its two ends; a fork thins and dims toward its tip.
        float taper = part < 0.5 ? 1.0 : 1.0 - 0.75 * along;
        float halfWidth = drawn ? CG_OBJECT_CUSTOM1.x * _Width * taper : 0.0;
        world = fx_ribbon_vertex(world, tangent, FX_CAMERA, halfWidth, side);
        float ends = part < 0.5 ? smoothstep(0.0, 0.05, along) * smoothstep(1.0, 0.95, along)
                                : 0.6 * (1.0 - smoothstep(0.6, 1.0, along));
        o.world = world;
        o.bolt = vec2(side, fx_bolt_strobe(moment, boltSeed) * ends);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 l = fx_bolt_profile(i.bolt.x, fwidth(i.bolt.x), _Core);
        vec3 col = CG_OBJECT_CUSTOM3.rgb * l.x * 2.6 + CG_OBJECT_CUSTOM2.rgb * (l.y * 0.55 + l.z * 0.3);
        fragColor = vec4(col * i.bolt.y * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z, 1.0);
    }
}
