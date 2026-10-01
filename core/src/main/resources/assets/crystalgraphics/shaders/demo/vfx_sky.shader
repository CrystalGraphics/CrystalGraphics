// The showcase's sky: the studio the spheres reflect, over deep space -- stars and a slow violet nebula. Drawn on a
// sphere around the camera, seen from inside and pushed to the far plane so everything draws in front of it.
// CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Background" }
Queue = "Background"

struct v2f { vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.dir = world.xyz - VFX_CAMERA;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
        gl_Position.z = gl_Position.w * 0.99999;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 d = normalize(i.dir);
        vec3 color = vfx_env(d, 0.0);
        float above = smoothstep(-0.02, 0.15, d.y);
        // Stars: one in a few hundred cells of the sky, each twinkling at its own rate.
        vec3 cell = floor(d * 260.0);
        float seed = vfx_hash31(cell);
        float star = step(0.993, seed) * (0.5 + 0.5 * sin(t * (1.5 + seed * 4.0) + seed * 50.0));
        vec3 starTint = mix(vec3(0.7, 0.8, 1.2), vec3(1.2, 0.9, 0.7), vfx_hash31(cell + 5.0));
        // The nebula: warped noise, violet and teal, brightest along a band.
        vec3 q = d * 2.2 + vec3(t * 0.01, 0.0, 0.0);
        float cloud = vfx_fbm(q + vfx_fbm(q * 1.7, 3) * 1.8, 5);
        float band = exp(-pow(d.y - 0.35 - 0.2 * d.x, 2.0) * 6.0);
        vec3 nebula = mix(vec3(0.35, 0.08, 0.55), vec3(0.05, 0.4, 0.5), vfx_fbm(q * 0.8, 3)) * pow(cloud, 2.5) * band * 2.2;
        color += (starTint * star * 2.5 + nebula) * above;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
