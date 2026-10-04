// An energy orb as plasma (a wave's charge, then its root): a volume marched along each view ray, dense and white-hot at
// the centre and falling smoothly through cyan to blue at the edge, so it has no hard rim and no gaps. Filaments inside
// it pour inward on two cross-faded phases, so the motion never winds up, and turn with it. Drawn on
// CgVfxFrame.mesh's sphere's far wall, each pixel once; the opaque scene hides it by depth. CG_OBJECT_CUSTOM1.z is an
// intensity. Colour A is the hot heart, colour B the cool edge, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Inflow     ("How fast filaments pour inward, cycles a second", float) = 1.5
    _Spin       ("How fast it turns, radians a second", float) = 2.6
    _Scale      ("Filament frequency", float) = 2.6
    _Brightness ("Emission", float) = 1.45
    _Noise      ("Noise", sampler3D) = "cg_noise"
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    // Filaments at p (inside the unit sphere): ridged noise, pouring inward on two phases half a cycle apart.
    float filaments(vec3 p, float age, float seed) {
        float phase = fract(age * _Inflow);
        float sum = 0.0;
        for (int k = 0; k < 2; k++) {
            float ph = fract(phase + 0.5 * float(k));
            float weight = 1.0 - abs(2.0 * ph - 1.0);
            // Growing the lookup as the phase rises draws each feature toward the centre.
            vec3 q = fx_rotate_z(p * exp2(ph), age * _Spin) * _Scale + seed * 17.0 + float(k) * 31.0;
            float n = fx_fbm(q, 3);
            float ridge = 1.0 - abs(n);
            float r4 = ridge * ridge * ridge * ridge;
            sum += weight * r4 * (1.0 + 0.8 * ridge * ridge);
        }
        return sum;
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
        float t0 = max(-b - h, 0.0), t1 = -b + h;
        if (t1 <= t0) discard;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        const int STEPS = 14;
        float stride = (t1 - t0) / float(STEPS);
        // One fixed dither per pixel, so the steps leave no bands and nothing shimmers.
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        vec3 hot = CG_OBJECT_CUSTOM2.rgb, cool = CG_OBJECT_CUSTOM3.rgb;
        vec3 mid = mix(cool, hot, 0.45) * 1.15;
        // The heart throbs, a violent surge several times a second.
        float throb = 0.75 + 0.5 * fx_value_noise(vec3(seed * 7.0, 1.5, 0.0) + cg_noise_time(age * 9.0));
        vec3 sum = vec3(0.0);
        for (int s = 0; s < STEPS; s++) {
            vec3 p = o + d * (t0 + (float(s) + jitter) * stride);
            float r2 = dot(p, p);
            if (r2 >= 1.0) continue;
            float falloff = pow(1.0 - r2, 1.6);
            float heart = exp(-r2 * 14.0 / throb);
            float density = falloff * (0.15 + 1.5 * filaments(p, age, seed)) + heart * 0.9;
            float heat = clamp(density * 0.85, 0.0, 1.0);
            vec3 colour = mix(cool, mid, smoothstep(0.05, 0.45, heat));
            colour = mix(colour, hot, smoothstep(0.5, 0.95, heat));
            sum += colour * density * stride;
        }
        // A thin cool corona where the ray grazes the ball's edge, so it has a defined rim and no hard one.
        float graze = length(o - d * dot(o, d));
        vec3 gp = o - d * dot(o, d);
        float torn = 0.5 + 0.5 * fx_noise(fx_rotate_z(gp, age * _Spin) * 4.0 + vec3(0.0, 0.0, age * 3.0) + seed);
        sum += mix(cool, hot, 0.3) * 0.9 * torn * torn * exp(-pow((graze - 0.8 - 0.06 * torn) / 0.07, 2.0));
        // The orb's centre against the opaque scene, softened over the orb's own size.
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        float seen = smoothstep(-radius, radius * 0.5, FX_SCENE_DISTANCE(ray) - dot(centre - eye, ray));
        fragColor = vec4(sum * _Brightness * fx_flicker(age, seed) * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * seen, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
