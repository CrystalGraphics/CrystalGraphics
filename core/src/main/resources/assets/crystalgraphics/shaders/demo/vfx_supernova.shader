// A supernova's heart: fire boiling over itself, deep ember red through orange and gold to white-hot where it
// faces you, a blazing rim, dark red flecks swept along the flow and violet lightning crawling across it. Its
// corona is vfx_supernova_corona.shader. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"
#include "crystalgraphics:shaders/demo/vfx_fire.glsl"

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

    // Violet lightning over the surface: a new strike {@code rate} times a second, flickering while it lasts.
    float supernova_arcs(vec3 p, float t, float seed, float rate) {
        float strike = floor(t * rate);
        vec3 q = p * 1.8 + vfx_hash33(vec3(strike, seed, 7.0)) * 40.0;
        q += vec3(vfx_noise(q * 2.5 + t * 3.0), vfx_noise(q * 2.5 - t * 3.0), 0.0) * 0.4;
        float bolt = smoothstep(0.84, 0.98, vfx_ridged(q, 4));
        float lit = step(0.4, vfx_hash31(vec3(strike, seed, 3.0)));
        return bolt * lit * (0.6 + 0.4 * sin(t * 70.0 + seed * 9.0));
    }

    void vertex(out v2f o) {
        // The surface boils: a slow swell outward where the fire runs hottest.
        float boil = vfx_fbm(cg_Position * 2.4 + vec3(0.0, CG_TIME * 0.6, 0.0), 3);
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position * (1.0 + (boil - 0.5) * 0.09), 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 p = i.objPos;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        // Fire folding over itself: noise warped by noise, flowing up and out.
        vec3 q = p * 2.3;
        vec3 warp = vec3(vfx_fbm(q + vec3(0.0, t * 0.35, 0.0), 4),
                         vfx_fbm(q + vec3(5.2, 1.3 - t * 0.3, 2.1), 4),
                         vfx_fbm(q + vec3(1.7, 9.2, t * 0.25), 4));
        float flames = vfx_fbm(q * 1.4 + warp * 2.4 - vec3(0.0, t * 0.7, 0.0), 6);
        float filaments = vfx_ridged(q * 2.2 + warp * 1.6 + vec3(t * 0.2, 0.0, -t * 0.15), 4);
        float pulse = 1.0 + 0.12 * sin(t * 2.1) + 0.06 * sin(t * 5.3);
        float heat = smoothstep(0.3, 0.85, flames) * 0.78 + filaments * 0.22 + pow(nv, 3.0) * 0.16;
        vec3 color = vfx_fire(clamp(heat, 0.0, 1.0)) * pulse;
        // Dark red flecks swept along with the flow.
        vec3 cells = vfx_voronoi(p * 9.0 + warp * 2.0 + vec3(0.0, -t * 0.4, 0.0));
        float fleck = smoothstep(0.14, 0.05, cells.x) * step(0.72, cells.z);
        color = mix(color, vec3(0.6, 0.03, 0.0), fleck * 0.85);
        // White-hot where it faces you, a blazing rim where it turns away.
        color += vec3(2.6, 2.2, 1.5) * pow(nv, 10.0) * 0.45 * pulse;
        color += vec3(2.6, 0.7, 0.06) * pow(1.0 - nv, 2.5) * 1.4;
        // Violet lightning, white along its spine.
        float arcs = supernova_arcs(p, t, 1.0, 6.0) + supernova_arcs(p * 1.3, t, 2.0, 4.5) * 0.8;
        color += vec3(1.6, 0.5, 2.8) * arcs * 3.0 + vec3(1.0) * pow(arcs, 2.0) * 3.0;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
