// A plasma globe: a dark glass ball, lightning crawling across its inside wall, re-striking many times a second and
// lighting the glass up around it. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // One family of bolts: thin bright lines where ridged noise peaks, re-seeded every {@code rate}th of a second.
    float storm_bolts(vec3 p, float t, float seed, float rate) {
        float strike = floor(t * rate);
        float flash = vfx_hash31(vec3(strike, seed, 1.0));
        vec3 q = p * 2.2 + vfx_hash33(vec3(strike, seed, 2.0)) * 40.0;
        q += vec3(vfx_noise(q * 2.0 + t), vfx_noise(q * 2.0 - t), 0.0) * 0.35;
        float ridge = vfx_ridged(q, 4);
        float bolt = smoothstep(0.86, 0.99, ridge);
        return bolt * (0.4 + 1.6 * step(0.35, flash)) * (0.7 + 0.3 * sin(t * 90.0 + seed));
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        vec3 glass = vfx_pbr(n, v, vec3(0.02, 0.025, 0.05), 0.0, 0.08, 1.0);
        float bolts = storm_bolts(i.objPos, t, 1.0, 13.0) + storm_bolts(i.objPos, t, 2.0, 9.0) * 0.8
                + storm_bolts(i.objPos * 1.3, t, 3.0, 17.0) * 0.6;
        // The core, seen through the glass: brightest straight in.
        float core = pow(nv, 6.0) * (1.2 + 0.4 * sin(t * 23.0));
        // The glass around a strike glows with it.
        float halo = vfx_fbm(i.objPos * 3.0 + vec3(0.0, 0.0, floor(t * 13.0)), 3) * 0.6;
        vec3 electric = vec3(0.45, 0.75, 1.6);
        vec3 color = glass + electric * (bolts * 7.0 + core * 2.5 + halo * 0.5) + vec3(1.0) * pow(bolts, 2.0) * 4.0;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
