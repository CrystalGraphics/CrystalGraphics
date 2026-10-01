// Colour-shift car paint: crimson facing you turning violet and gold at the edges, metallic flakes glinting under a
// mirror clear coat. CgVfxShowcase.
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
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        // The flip-flop: the base colour follows the viewing angle.
        vec3 base = mix(vec3(0.95, 0.55, 0.12), vec3(0.42, 0.05, 0.55), smoothstep(0.0, 0.45, nv));
        base = mix(base, vec3(0.62, 0.015, 0.05), smoothstep(0.35, 0.9, nv));
        // Flakes: each cell a tiny mirror tilted its own way.
        vec3 cells = vfx_voronoi(i.objPos * 70.0);
        vec3 flakeNormal = normalize(n + (vfx_hash33(vec3(cells.z * 131.0)) - 0.5) * 0.9);
        float glint = pow(max(dot(reflect(-v, flakeNormal), VFX_KEY_DIR), 0.0), 220.0) * 14.0
                + pow(max(dot(reflect(-v, flakeNormal), VFX_RIM_DIR), 0.0), 260.0) * 10.0;
        vec3 paint = vfx_pbr(n, v, base, 0.55, 0.42, 1.0) + glint * mix(base, vec3(1.0), 0.6);
        // The clear coat over everything: a dielectric mirror.
        float coat = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        vec3 clear = vfx_env(reflect(-v, n), 0.02) + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.05);
        vec3 color = paint * (1.0 - coat) + clear * coat;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
