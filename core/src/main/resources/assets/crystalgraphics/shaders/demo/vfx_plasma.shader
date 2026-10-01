// A swirling plasma orb: twisted, domain-warped energy pouring around the sphere, white-hot where it is densest. The
// surface breathes. CgVfxShowcase.
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

    // The point twisted about the vertical axis, more the further from the equator, turning with time.
    vec3 plasma_twist(vec3 p, float t) {
        float angle = p.y * 2.4 + t * 0.9;
        float c = cos(angle), s = sin(angle);
        return vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
    }

    void vertex(out v2f o) {
        float t = CG_TIME;
        float pulse = 1.0 + 0.025 * sin(t * 3.1 + cg_Position.y * 5.0) + 0.02 * sin(t * 1.7);
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position * pulse, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 p = plasma_twist(i.objPos, t);
        vec3 warp = vec3(vfx_fbm(p * 1.6 + vec3(t * 0.21, 0.0, 0.0), 4), vfx_fbm(p * 1.6 + vec3(4.7, t * 0.17, 2.1), 4),
                vfx_fbm(p * 1.6 + vec3(1.3, 8.2, -t * 0.19), 4));
        float energy = vfx_fbm(p * 2.4 + warp * 3.2, 5);
        float filaments = vfx_ridged(p * 3.0 + warp * 2.0 + vec3(0.0, -t * 0.6, 0.0), 4);
        float heat = pow(clamp(energy * 1.35 - 0.1, 0.0, 1.0), 2.2) + pow(filaments, 6.0) * 1.6;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        // Magenta through violet to cyan, then white where it is hottest.
        vec3 tint = vfx_palette(energy * 0.8 + t * 0.03, vec3(0.55, 0.35, 0.65), vec3(0.45, 0.35, 0.4),
                vec3(1.0, 1.0, 1.0), vec3(0.85, 0.15, 0.55));
        vec3 color = tint * heat * 6.0 + vec3(1.0, 0.85, 1.0) * pow(heat, 3.0) * 6.0;
        // A hotter core seen through the middle, a softer rim.
        color *= 0.55 + 0.75 * nv;
        color += vec3(0.6, 0.15, 0.9) * pow(1.0 - nv, 3.0) * 1.5;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
