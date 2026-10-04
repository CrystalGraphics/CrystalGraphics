// Liquid mercury: a mirror whose surface swells and settles, its normal rebuilt per pixel from the moving surface
// itself. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 worldPos; vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    // The surface: the unit sphere swelling and settling under slow, broad noise.
    vec3 mercury_surface(vec3 dir, float t) {
        float h = fx_value_fbm(dir * 1.1 + vec3(0.0, t * 0.3, t * 0.18), 3);
        float wave = sin(dir.y * 4.0 + t * 1.8) * 0.5 + 0.5;
        return dir * (0.96 + 0.06 * h + 0.012 * wave);
    }

    // The surface's normal at {@code dir}, from the surface itself: per pixel, since a mirror shows every seam an
    // interpolated per-vertex normal leaves.
    vec3 mercury_normal(vec3 dir, float t) {
        vec3 side = normalize(abs(dir.y) < 0.99 ? cross(dir, vec3(0.0, 1.0, 0.0)) : cross(dir, vec3(1.0, 0.0, 0.0)));
        vec3 up = cross(side, dir);
        vec3 p0 = mercury_surface(dir, t);
        vec3 p1 = mercury_surface(normalize(dir + side * 0.01), t);
        vec3 p2 = mercury_surface(normalize(dir + up * 0.01), t);
        vec3 n = normalize(cross(p1 - p0, p2 - p0));
        return dot(n, dir) < 0.0 ? -n : n;
    }

    void vertex(out v2f o) {
        vec3 dir = normalize(cg_Position);
        vec4 world = CG_OBJECT_TO_WORLD * vec4(mercury_surface(dir, CG_TIME), 1.0);
        o.worldPos = world.xyz;
        o.dir = dir;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 n = normalize(CG_NORMAL_MATRIX * mercury_normal(normalize(i.dir), CG_TIME));
        // From inside the sphere its inner wall shows, facing in.
        if (!gl_FrontFacing) n = -n;
        vec3 v = normalize(FX_CAMERA - i.worldPos);
        float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
        vec3 color = vfx_pbr_studio(i.worldPos, floorY, n, v, vec3(0.92, 0.94, 0.97), 1.0, 0.04);
        fragColor = vec4(fx_aces(0.8 * color), 1.0);
    }
}
