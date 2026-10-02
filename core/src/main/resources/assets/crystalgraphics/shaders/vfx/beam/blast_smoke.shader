// The final blast's smoke: a billowing volume marched along each view ray and composited front to back, so it hides
// what is behind it; lit from within by the blast at first, then cooling to a dark haze that thins away from its edges.
// Marching stops at the opaque scene, so the ground cuts it cleanly. Drawn on CgVfxFrame.mesh's sphere's far wall.
// CG_OBJECT_CUSTOM1.z is an intensity, .w the blast's progress 0..1. Colour A is the inner glow, A's alpha a strength.
// CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Smoke   ("Smoke colour", color) = (0.11, 0.13, 0.19, 1.0)
    _Density ("Opacity per unit of the sphere", float) = 3.5
    _Scale   ("Billow frequency", float) = 2.2
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        mat3 toLocal = inverse(mat3(CG_OBJECT_TO_WORLD));
        vec3 o = toLocal * (eye - centre);
        vec3 dl = toLocal * ray;
        float scale = length(dl);
        vec3 d = dl / scale;
        float b = dot(o, d), c = dot(o, o) - 1.0, h = b * b - c;
        if (h <= 0.0) discard;
        h = sqrt(h);
        // Stop at the opaque scene: the scene's distance along the ray, in the sphere's units.
        float t0 = max(-b - h, 0.0), t1 = min(-b + h, FX_SCENE_DISTANCE(ray) * scale);
        if (t1 <= t0) discard;
        float progress = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w;
        const int STEPS = 12;
        float stride = (t1 - t0) / float(STEPS);
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        float thin = mix(0.35, 0.95, progress);
        vec3 glow = CG_OBJECT_CUSTOM2.rgb * CG_OBJECT_CUSTOM2.a * (1.0 - progress) * (1.0 - progress) * 2.0;
        vec3 colour = vec3(0.0);
        float transmit = 1.0;
        for (int s = 0; s < STEPS; s++) {
            vec3 p = o + d * (t0 + (float(s) + jitter) * stride);
            float r2 = dot(p, p);
            if (r2 >= 1.0) continue;
            float n = 0.5 + 0.5 * fx_fbm(p * _Scale + vec3(0.0, -progress * 1.2, 0.0) + seed * 13.0, 4);
            float density = smoothstep(thin, thin + 0.25, n * (1.0 - r2 * 0.6)) * (1.0 - r2);
            float a = 1.0 - exp(-density * stride * _Density);
            vec3 lit = _Smoke.rgb + glow * exp(-r2 * 3.0);
            colour += transmit * a * lit;
            transmit *= 1.0 - a;
        }
        float strength = CG_OBJECT_CUSTOM1.z;
        fragColor = vec4(colour * strength, (1.0 - transmit) * strength);
    }
}
