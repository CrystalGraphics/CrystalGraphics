// A neon circuit globe: dark gloss under a grid of light, pulses racing along the lines and nodes lighting where
// they cross. CgVfxShowcase.
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
        vec3 p = normalize(i.objPos);
        float lon = atan(p.z, p.x) / 6.28318 + 0.5;
        float lat = acos(clamp(p.y, -1.0, 1.0)) / 3.14159;
        vec2 uv = vec2(lon * 32.0, lat * 16.0);
        vec2 cell = floor(uv);
        vec2 local = fract(uv) - 0.5;
        vec2 width = fwidth(uv) * 1.2;
        // Lines: some rows and columns lit, the rest dark, so the grid reads as circuitry rather than graph paper.
        float lonLit = step(0.45, vfx_hash31(vec3(cell.x, 0.0, 1.0)));
        float latLit = step(0.45, vfx_hash31(vec3(0.0, cell.y, 2.0)));
        float lonLine = (1.0 - smoothstep(width.x, width.x * 2.0, abs(local.x))) * lonLit;
        float latLine = (1.0 - smoothstep(width.y, width.y * 2.0, abs(local.y))) * latLit;
        // Pulses: bright packets travelling along each lit line at its own speed.
        float lonPulse = pow(fract(uv.y * 0.12 - t * (0.4 + vfx_hash31(vec3(cell.x, 5.0, 0.0))) ), 18.0);
        float latPulse = pow(fract(uv.x * 0.06 + t * (0.3 + vfx_hash31(vec3(cell.y, 6.0, 0.0))) ), 18.0);
        float node = (1.0 - smoothstep(0.08, 0.14, length(local))) * lonLit * latLit;
        vec3 cyan = vec3(0.2, 0.9, 1.6);
        vec3 orange = vec3(1.7, 0.55, 0.12);
        vec3 lineColor = mix(cyan, orange, step(0.7, vfx_hash31(vec3(cell, 4.0))));
        vec3 glow = lineColor * (lonLine * (0.35 + lonPulse * 6.0) + latLine * (0.35 + latPulse * 6.0))
                + vec3(1.2, 1.0, 1.6) * node * (0.6 + 0.4 * sin(t * 3.0 + cell.x));
        vec3 shell = vfx_pbr(n, v, vec3(0.015, 0.018, 0.03), 0.3, 0.22, 1.0);
        fragColor = vec4(vfx_aces(shell + glow * 2.0), 1.0);
    }
}
