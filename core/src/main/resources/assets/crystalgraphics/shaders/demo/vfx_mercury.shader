// Liquid mercury: a mirror whose surface swells and settles, its normal rebuilt per pixel from the moving surface
// itself. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

Properties {
    _ValueGradient ("Value slope", sampler3D) = "cg_value_gradient"
}

struct v2f { vec3 worldPos; vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    // The unit sphere swelling and settling under slow, broad noise: xyz the radius's gradient over dir, w the radius.
    vec4 mercury_radius(vec3 dir, float t) {
        vec4 h = fx_value_fbm_grad(dir * 1.1 + vec3(0.0, t * 0.3, t * 0.18), 3);
        float phase = dir.y * 4.0 + t * 1.8;
        float radius = 0.96 + 0.06 * h.w + 0.012 * (sin(phase) * 0.5 + 0.5);
        return vec4(0.066 * h.xyz + vec3(0.0, 0.024 * cos(phase), 0.0), radius);
    }

    vec3 mercury_surface(vec3 dir, float t) {
        return dir * mercury_radius(dir, t).w;
    }

    // The surface's normal at {@code dir}, per pixel from the noise's own slope: a mirror shows every crease that
    // differencing filtered noise leaves at its texels.
    vec3 mercury_normal(vec3 dir, float t) {
        vec4 r = mercury_radius(dir, t);
        return normalize(dir - (r.xyz - dot(r.xyz, dir) * dir) / r.w);
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
