// Sparks thrown off an impact: streaks flying out over the hemisphere the draw's +z faces, slowed and pulled down by
// gravity, white-hot heads and fading tails. Stateless (CgVfxRibbons). Continuous while _Burst is 0, each spark relaunched
// when its life ends; one burst at CG_OBJECT_CUSTOM1.w seconds ago when _Burst is 1. CG_OBJECT_CUSTOM1.z is an intensity.
// Colour A is a spark's sheath, colour B its hot head, A's alpha a strength. CgEnergyWave.
#type none
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Count   ("Sparks drawn", float) = 64.0
    _Life    ("Seconds a spark lives", float) = 0.55
    _Speed   ("Launch speed, blocks a second", float) = 12.0
    _Gravity ("Pull downward, blocks a second squared", float) = 14.0
    _Streak  ("Streak length, seconds of flight", float) = 0.05
    _Width   ("Half-width, blocks", float) = 0.05
    _Burst   ("1 for one burst, 0 for a steady spray", float) = 0.0
}

struct v2f { vec3 world; vec3 spark; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // Where a spark is t seconds after leaving centre along dir (unit, world) at speed.
    vec3 flight(vec3 centre, vec3 dir, float speed, float life, float t) {
        t = max(t, 0.0);
        float slowed = t * (1.0 - 0.35 * min(t / life, 1.0));
        return centre + dir * speed * slowed + vec3(0.0, -0.5 * _Gravity * t * t, 0.0);
    }

    void vertex(out v2f o) {
        float index = FX_RIBBON_INDEX, along = FX_RIBBON_ALONG, side = FX_RIBBON_SIDE;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        vec4 h = fx_hash41(index * 1.73 + seed * 61.0);
        float life = _Life * (0.6 + 0.8 * h.x);
        float t, epoch;
        if (_Burst > 0.5) {
            t = CG_OBJECT_CUSTOM1.w * (0.8 + 0.4 * h.y);
            epoch = 0.0;
        } else {
            float cycle = age / life + h.y;
            t = fract(cycle) * life;
            epoch = floor(cycle);
        }
        vec4 d = fx_hash41(index * 3.91 + epoch * 5.17 + seed * 23.0);
        // Biased toward +z, out over its hemisphere.
        float z = 0.15 + 0.85 * d.x;
        float r = sqrt(max(1.0 - z * z, 0.0)), a = d.y * 6.28318531;
        mat3 basis = mat3(normalize(CG_OBJECT_TO_WORLD[0].xyz), normalize(CG_OBJECT_TO_WORLD[1].xyz), normalize(CG_OBJECT_TO_WORLD[2].xyz));
        vec3 dir = basis * vec3(r * cos(a), r * sin(a), z);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float speed = _Speed * (0.5 + d.z);
        float streak = _Streak * (0.6 + 0.8 * h.w);
        float back = t - streak * (1.0 - along);
        vec3 world = flight(centre, dir, speed, life, back);
        vec3 tangent = flight(centre, dir, speed, life, back + 0.01) - world;
        float spindle = pow(sin(3.14159265 * along), 0.6) * (0.35 + 0.65 * along);
        bool alive = index < _Count && t < life && CG_OBJECT_CUSTOM1.z > 0.0;
        world = fx_ribbon_vertex(world, tangent, FX_CAMERA, alive ? _Width * spindle * (0.7 + 0.6 * h.z) : 0.0, side);
        float brightness = smoothstep(0.0, 0.04, t) * (1.0 - smoothstep(0.55, 1.0, t / life));
        o.world = world;
        o.spark = vec3(along, side, brightness * (1.0 - 0.5 * t / life));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float along = i.spark.x, across = i.spark.y;
        float core = exp(-across * across * 9.0);
        float sheath = exp(-across * across * 2.5) * 0.5;
        vec3 col = (CG_OBJECT_CUSTOM2.rgb * sheath + CG_OBJECT_CUSTOM3.rgb * core * along) * pow(along, 1.3);
        fragColor = vec4(col * i.spark.z * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z, 1.0);
    }
}
