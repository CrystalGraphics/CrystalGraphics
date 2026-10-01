// A crystal ball: the studio seen through solid glass, refracted in and out, upside down and split into a spectrum at
// the rim. CgVfxShowcase.
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

    // One wavelength through the ball: refracted in at {@code p}, crossed, refracted out, and what it then sees.
    vec3 crystal_through(vec3 p, vec3 n, vec3 v, vec3 centre, float ior) {
        vec3 inside = refract(-v, n, 1.0 / ior);
        float across = -2.0 * dot(p - centre, inside);
        vec3 exitPoint = p + inside * across;
        vec3 exitNormal = normalize(exitPoint - centre);
        vec3 leaving = refract(inside, -exitNormal, ior);
        if (dot(leaving, leaving) < 1.0e-4) leaving = reflect(inside, -exitNormal);
        return vfx_env(leaving, 0.0);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        vec3 through = vec3(crystal_through(i.worldPos, n, v, centre, 1.47).r,
                crystal_through(i.worldPos, n, v, centre, 1.51).g,
                crystal_through(i.worldPos, n, v, centre, 1.56).b);
        float nv = max(dot(n, v), 0.0);
        float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        vec3 reflection = vfx_env(reflect(-v, n), 0.0)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.03) * 2.0;
        // A faint cyan body and a caustic hot spot where the ball focuses the key light.
        vec3 body = through * vec3(0.86, 0.96, 1.0) * 1.15;
        float caustic = pow(max(dot(-n, VFX_KEY_DIR), 0.0), 18.0) * 2.5;
        vec3 color = mix(body, reflection, fresnel) + vec3(1.0, 0.95, 0.85) * caustic;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
