// Brushed copper going green: rings of brushing around the pole, and verdigris creeping in from the cracks.
// CgVfxShowcase.
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
        vec3 p = i.objPos;
        // Brushing: fine lines along each latitude, so a highlight smears around the sphere.
        float brush = vfx_noise(vec3(p.y * 140.0, atan(p.z, p.x) * 2.0, 0.0));
        float patina = smoothstep(0.52, 0.66, vfx_fbm(p * 2.2 + vec3(3.1), 5) + 0.15 * vfx_ridged(p * 6.0, 3));
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        vec3 copper = vec3(0.955, 0.638, 0.538) * (0.85 + 0.25 * brush);
        vec3 metal = vfx_pbr(n, v, copper, 1.0, 0.26 + 0.12 * brush, 1.0);
        vec3 verdigris = vfx_pbr(n, v, vec3(0.20, 0.62, 0.52) * (0.7 + 0.3 * vfx_noise(p * 30.0)), 0.0, 0.85, 0.9);
        vec3 color = mix(metal, verdigris, patina);
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
