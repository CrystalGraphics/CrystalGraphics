// A crystal ball: solid glass showing the studio and the floor upside down and magnified, split into a spectrum at its
// rim, with a wisp of violet mist turning in its heart. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 worldPos; vec3 normalWs; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    // One wavelength leaving the glass at {@code p}, travelling {@code d} into a wall whose outward normal is
    // {@code wall}: refracted out, or reflected back where it meets the wall too steeply, and the studio it then sees.
    vec3 crystal_out(vec3 p, vec3 d, vec3 wall, float ior, float floorY) {
        vec3 leaving = refract(d, -wall, ior);
        if (dot(leaving, leaving) < 1.0e-4) leaving = reflect(d, -wall);
        return vfx_studio(p, leaving, 0.0, floorY);
    }

    // One wavelength through the ball: refracted in at {@code p}, across, refracted out, and the studio it then sees.
    vec3 crystal_through(vec3 p, vec3 n, vec3 v, vec3 centre, float ior, float floorY) {
        vec3 inside = refract(-v, n, 1.0 / ior);
        float across = -2.0 * dot(p - centre, inside);
        vec3 exitPoint = p + inside * across;
        return crystal_out(exitPoint, inside, normalize(exitPoint - centre), ior, floorY);
    }

    // The mist along a ray through the ball, in the ball's own frame ({@code q} on the unit sphere): soft wisps
    // turning about the centre, faint toward the glass.
    vec3 crystal_mist(vec3 q, vec3 d, float across, float t) {
        vec3 light = vec3(0.0);
        for (int k = 0; k < 12; k++) {
            vec3 x = q + d * ((float(k) + 0.5) / 12.0 * across);
            float r = length(x);
            float a = t * 0.35 + r * 2.5;
            vec3 swirl = vec3(x.x * cos(a) - x.z * sin(a), x.y, x.x * sin(a) + x.z * cos(a));
            float density = smoothstep(0.42, 0.75, fx_value_fbm(swirl * 2.4 + vec3(0.0, t * 0.12, 0.0), 4));
            density *= 1.0 - smoothstep(0.2, 0.85, r);
            light += mix(vec3(0.55, 0.12, 1.0), vec3(0.15, 0.55, 1.0), x.y * 0.5 + 0.5) * density;
        }
        return light * across / 12.0 * 1.6;
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        float floorY = centre.y - CG_OBJECT_CUSTOM3.x;
        vec3 camera = FX_CAMERA;
        vec3 wall = normalize(i.normalWs);
        vec3 n = gl_FrontFacing ? wall : -wall;
        vec3 v = normalize(camera - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        // The mist is in the ball's frame, so it turns with it.
        mat3 toObject = transpose(mat3(CG_OBJECT_TO_WORLD)) / (radius * radius);
        vec3 through, mist;
        if (gl_FrontFacing) {
            through = vec3(crystal_through(i.worldPos, n, v, centre, 1.47, floorY).r,
                    crystal_through(i.worldPos, n, v, centre, 1.51, floorY).g,
                    crystal_through(i.worldPos, n, v, centre, 1.56, floorY).b);
            vec3 inside = refract(-v, n, 1.0 / 1.51);
            float across = -2.0 * dot(i.worldPos - centre, inside) / radius;
            mist = crystal_mist(toObject * (i.worldPos - centre), normalize(toObject * inside), across, t);
        } else {
            // From inside the eye is in the glass, and the ray leaves it at the wall ahead.
            through = vec3(crystal_out(i.worldPos, -v, wall, 1.47, floorY).r,
                    crystal_out(i.worldPos, -v, wall, 1.51, floorY).g,
                    crystal_out(i.worldPos, -v, wall, 1.56, floorY).b);
            mist = crystal_mist(toObject * (camera - centre), normalize(toObject * -v), length(i.worldPos - camera) / radius, t);
        }
        float fresnel = (0.04 + 0.96 * pow(1.0 - nv, 5.0)) * (gl_FrontFacing ? 1.0 : 0.25);
        vec3 reflection = vfx_studio(i.worldPos, reflect(-v, n), 0.0, floorY)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.03) * 2.0
                + vfx_direct(n, v, VFX_RIM_DIR, VFX_RIM_COLOR, vec3(1.0), 0.0, 0.03);
        vec3 body = through * vec3(0.92, 0.97, 1.0) + mist;
        // A caustic hot spot where the ball focuses the key light.
        float caustic = gl_FrontFacing ? pow(max(dot(-n, VFX_KEY_DIR), 0.0), 18.0) * 2.0 : 0.0;
        vec3 color = mix(body, reflection, fresnel) + vec3(1.0, 0.95, 0.85) * caustic;
        fragColor = vec4(fx_aces(0.8 * color), 1.0);
    }
}
