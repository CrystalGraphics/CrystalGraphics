// Liquid mercury: a mirror whose surface rolls and wobbles, the normals rebuilt from the moving surface itself.
// CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 normalWs; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // The surface: the unit sphere pushed in and out by slow noise.
    vec3 mercury_surface(vec3 dir, float t) {
        float h = vfx_fbm(dir * 1.7 + vec3(0.0, t * 0.35, t * 0.2), 3);
        float wave = sin(dir.y * 6.0 + t * 2.3) * 0.5 + 0.5;
        return dir * (0.94 + 0.12 * h + 0.025 * wave);
    }

    void vertex(out v2f o) {
        float t = CG_TIME;
        vec3 dir = normalize(cg_Position);
        vec3 side = normalize(abs(dir.y) < 0.99 ? cross(dir, vec3(0.0, 1.0, 0.0)) : cross(dir, vec3(1.0, 0.0, 0.0)));
        vec3 up = cross(side, dir);
        vec3 p0 = mercury_surface(dir, t);
        vec3 p1 = mercury_surface(normalize(dir + side * 0.02), t);
        vec3 p2 = mercury_surface(normalize(dir + up * 0.02), t);
        vec3 normalObj = normalize(cross(p1 - p0, p2 - p0));
        if (dot(normalObj, dir) < 0.0) normalObj = -normalObj;
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p0, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * normalObj;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        vec3 color = vfx_pbr(n, v, vec3(0.92, 0.94, 0.97), 1.0, 0.05, 1.0);
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
