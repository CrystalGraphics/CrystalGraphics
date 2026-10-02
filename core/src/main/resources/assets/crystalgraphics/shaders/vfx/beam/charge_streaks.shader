// Streaks of energy drawn into a charging orb: each a tapered, curved ribbon falling from the edge of the draw's sphere
// toward the orb, accelerating and spiralling about the aim as it goes, then swallowed. Stateless (CgVfxRibbons):
// a ribbon's index hashes into its direction, life and spin. CG_OBJECT_CUSTOM1: x the orb's radius in blocks, y the
// orb's radius as a share of the sphere the streaks start on, z an intensity. Colour A is a streak, colour B its hot
// head, A's alpha a strength. CgEnergyWave.
#type none
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count ("Streaks drawn", float) = 40.0
    _Life  ("Seconds each takes to fall in", float) = 0.42
    _Twist ("Turns about the aim as it falls", float) = 0.35
    _Trail ("Length, share of its fall", float) = 0.45
    _Width ("Half-width at its widest, share of the orb's radius", float) = 0.03
}

struct v2f { vec3 world; vec3 streak; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // Where streak h is at u through its fall (0 its start, 1 at the orb), in the sphere's unit space.
    vec3 fall(vec4 h, vec3 start, float u, float inner) {
        u = clamp(u, 0.0, 1.0);
        float k = u * u;
        float r = mix(1.0, inner * 0.8, k);
        float spin = (h.w < 0.5 ? -1.0 : 1.0) * _Twist * 6.28318531 * k;
        return fx_rotate_z(start, spin) * r;
    }

    void vertex(out v2f o) {
        float index = FX_RIBBON_INDEX, along = FX_RIBBON_ALONG, side = FX_RIBBON_SIDE;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        vec4 h = fx_hash41(index * 1.618 + seed * 113.0);
        vec4 g = fx_hash41(index * 2.414 + seed * 71.0 + 5.0);
        float life = _Life * (0.65 + 0.7 * h.x);
        float cycle = age / life + h.y;
        float phase = fract(cycle);
        // A new direction every fall, so the streaks never retrace one another.
        vec4 d = fx_hash41(index * 3.17 + floor(cycle) * 7.31 + seed * 29.0);
        vec3 start = fx_sphere_dir(d.xy);
        float inner = CG_OBJECT_CUSTOM1.y;
        float u = phase - _Trail * (1.0 - along);
        vec3 p = fall(h, start, u, inner);
        vec3 ahead = fall(h, start, u + 0.02, inner);
        vec3 world = (CG_OBJECT_TO_WORLD * vec4(p, 1.0)).xyz;
        vec3 tangent = mat3(CG_OBJECT_TO_WORLD) * (ahead - p);
        bool drawn = index < _Count;
        // A spindle: pointed at both ends, widest toward its head.
        float spindle = pow(sin(3.14159265 * along), 0.7) * (0.4 + 0.6 * along);
        float halfWidth = drawn ? CG_OBJECT_CUSTOM1.x * _Width * spindle * (0.7 + 0.6 * g.x) : 0.0;
        world = fx_ribbon_vertex(world, tangent, FX_CAMERA, halfWidth, side);
        // Fades in as it leaves, out as the orb swallows it.
        float brightness = smoothstep(0.0, 0.35, phase) * (1.0 - smoothstep(0.85, 1.0, phase)) * (0.6 + 0.8 * g.y);
        o.world = world;
        o.streak = vec3(along, side, brightness);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float along = i.streak.x, across = i.streak.y;
        // A white core line inside a cyan sheath, both soft across the ribbon, the tail fading to nothing.
        float core = exp(-across * across * 9.0);
        float sheath = exp(-across * across * 2.5) * 0.45;
        float lengthwise = pow(along, 1.5);
        vec3 col = (CG_OBJECT_CUSTOM2.rgb * sheath + CG_OBJECT_CUSTOM3.rgb * core * along) * lengthwise;
        fragColor = vec4(col * i.streak.z * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z, 1.0);
    }
}
