// Hammered gold: physically based, its roughness varying cell to cell as beaten metal does. CgVfxShowcase.
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
        vec3 cells = vfx_voronoi(i.objPos * 6.5);
        // Each hammer strike tilts its dimple: the normal leans away from the cell's centre.
        vec3 jitter = (vfx_hash33(vec3(cells.z * 97.0)) - 0.5) * 0.18 * smoothstep(0.0, 0.5, cells.x);
        vec3 n = normalize(normalize(i.normalWs) + jitter);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float rough = 0.12 + 0.10 * cells.z + 0.08 * smoothstep(0.02, 0.0, cells.y - cells.x);
        vec3 albedo = vec3(1.0, 0.766, 0.336);
        vec3 color = vfx_pbr(n, v, albedo, 1.0, rough, 1.0);
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
