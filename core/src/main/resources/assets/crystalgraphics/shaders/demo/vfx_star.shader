// A star: boiling granulation, dark sunspots drifting across it, darker towards the limb where you see its cooler
// upper layers. CgVfxShowcase.
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
        vec3 p = i.objPos;
        // Granules: convection cells, bright centres and dark lanes, churning slowly.
        vec3 cells = vfx_voronoi(p * 11.0 + vec3(0.0, t * 0.08, 0.0) + vfx_fbm(p * 3.0 + t * 0.1, 3) * 1.5);
        float granule = smoothstep(0.0, 0.55, cells.y - cells.x);
        float turbulence = vfx_fbm(p * 5.0 + vec3(t * 0.12, -t * 0.07, 0.0), 5);
        float spots = smoothstep(0.62, 0.72, vfx_fbm(p * 1.6 + vec3(t * 0.02, 0.0, 5.0), 4));
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        float limb = 0.35 + 0.65 * pow(nv, 0.55);
        vec3 hot = vec3(5.0, 3.6, 1.6);
        vec3 cool = vec3(2.6, 0.9, 0.18);
        vec3 surface = mix(cool, hot, granule * 0.7 + turbulence * 0.5);
        surface = mix(surface, vec3(0.35, 0.08, 0.01), spots * (0.6 + 0.4 * granule));
        vec3 color = surface * limb + vec3(2.0, 0.7, 0.15) * pow(1.0 - nv, 4.0) * 2.0;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
