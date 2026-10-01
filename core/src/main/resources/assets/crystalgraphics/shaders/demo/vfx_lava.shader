// A lava world: plates of cooling basalt riding on magma, the cracks between them glowing and pulsing, the plates
// themselves raised off the surface. CgVfxShowcase.
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

    // How far a point sits from the nearest crack, 0 on it.
    float lava_crack(vec3 p) {
        vec3 cells = vfx_voronoi(p * 3.0);
        return cells.y - cells.x;
    }

    void vertex(out v2f o) {
        vec3 dir = normalize(cg_Position);
        float plate = smoothstep(0.0, 0.25, lava_crack(dir));
        vec4 world = CG_OBJECT_TO_WORLD * vec4(dir * (0.97 + 0.04 * plate), 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = dir;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float crack = lava_crack(i.objPos);
        float rock = vfx_fbm(i.objPos * 9.0, 5);
        vec3 basalt = vfx_pbr(n, v, vec3(0.05, 0.045, 0.045) + vec3(0.04) * rock, 0.0, 0.85, 0.8);
        // The magma: flowing noise along the cracks, pulsing.
        float flow = vfx_fbm(i.objPos * 6.0 + vec3(0.0, t * 0.4, t * 0.25), 4);
        float pulse = 0.75 + 0.25 * sin(t * 2.2 + flow * 6.0);
        float molten = 1.0 - smoothstep(0.0, 0.11, crack);
        float glow = 1.0 - smoothstep(0.0, 0.32, crack);
        vec3 magma = mix(vec3(1.6, 0.18, 0.02), vec3(4.0, 2.2, 0.45), flow) * pulse;
        // Hot rims on the plates, cooling inward, and a dim heat across the basalt.
        vec3 color = basalt + magma * molten * 3.5 + vec3(1.2, 0.25, 0.03) * glow * 0.9 * pulse
                + vec3(0.25, 0.04, 0.0) * pow(rock, 3.0);
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
