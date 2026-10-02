// A black hole: each pixel traces a light ray along its orbit round the hole -- a Schwarzschild null geodesic, as
// rantonels' starless integrates it -- through a thin accretion disk hottest just inside, brighter and bluer on the
// side coming toward you and reddened near the horizon. The disk's far side is lensed up over the shadow and under it,
// a photon ring hugs the shadow, and the stars behind are smeared round it. Nothing of the sphere it is traced in shows:
// what the rays do not catch is the scene behind. A ray ends where the scene's depth says it meets something solid, so
// the floor and anything in front cut the disk where they really are. Lengths in the shader are in Schwarzschild
// radii. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_halo.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    // Light at temperature {@code x}, about 1 for the disk's hottest: Gargantua's palette -- deep amber at the cool
    // outer edge, gold, white-hot near the hole, a touch of blue where the approaching side runs hotter still.
    vec3 bh_blackbody(float x) {
        vec3 c = mix(vec3(0.55, 0.08, 0.01), vec3(2.2, 0.75, 0.15), smoothstep(0.05, 0.4, x));
        c = mix(c, vec3(2.8, 1.85, 0.75), smoothstep(0.35, 0.7, x));
        c = mix(c, vec3(3.0, 2.75, 2.3), smoothstep(0.7, 1.05, x));
        return mix(c, vec3(2.5, 2.7, 3.3), smoothstep(1.2, 1.8, x));
    }

    // The disk's turbulence at angle {@code a} and radius {@code r}: streaks drawn out along the orbit.
    float bh_streaks(float a, float r) {
        vec3 q = vec3(cos(a) * 1.6, sin(a) * 1.6, log(r) * 9.0);
        return fx_value_fbm(q, 4) * 0.75 + fx_value_noise(q * 3.1) * 0.25;
    }

    // The stars and the faint nebula a lensed ray sees, in world space.
    vec3 bh_sky(vec3 d) {
        vec3 s = d * 120.0;
        vec3 cell = floor(s);
        vec3 at = fract(s) - 0.5 - (fx_hash33(cell) - 0.5) * 0.6;
        float star = step(0.97, fx_hash31(cell)) * exp(-dot(at, at) * 60.0);
        vec3 nebula = mix(vec3(0.25, 0.05, 0.4), vec3(0.05, 0.25, 0.45), fx_value_fbm(d * 2.0, 3)) * pow(fx_value_fbm(d * 3.0 + 4.0, 4), 2.0);
        return vec3(1.2, 1.1, 1.0) * star * 2.5 + nebula * 0.6 + vec3(0.004, 0.004, 0.01);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        const float RS = 0.1;       // the Schwarzschild radius, in the sphere's radii
        const float DISK_IN = 3.0, DISK_OUT = 9.6;
        mat3 model = mat3(CG_OBJECT_TO_WORLD);
        mat3 toObject = inverse(model);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 camera = FX_CAMERA;
        vec3 inWorld = normalize(i.worldPos - camera);
        // The ray in the hole's own frame, where the disk is the plane y = 0, starting where it enters the sphere, or
        // at the eye from inside it.
        vec3 eye = toObject * (camera - centre);
        vec3 dir = normalize(toObject * inWorld);
        float b = dot(eye, dir);
        float enter = max(-b - sqrt(max(b * b - (dot(eye, eye) - 1.0), 0.0)), 0.0);
        vec3 x = (eye + dir * enter) / RS;
        // How far the ray may go before the scene stops it, in Schwarzschild radii from where it entered.
        float scale = length(CG_OBJECT_TO_WORLD[0].xyz);
        float budget = (VFX_SCENE_DISTANCE(inWorld) / scale - enter) / RS;
        if (budget <= 0.0) discard;
        float travelled = 0.0;
        bool blocked = false;
        vec3 v = dir;
        vec3 l = cross(x, v);
        float h2 = dot(l, l);
        vec3 light = vec3(0.0);
        float transmit = 1.0, nearest = 1.0e3;
        bool swallowed = false;
        float cycle = 6.0;
        float phaseA = fract(t / cycle), phaseB = fract(t / cycle + 0.5);
        float weightA = 1.0 - abs(2.0 * phaseA - 1.0);
        for (int k = 0; k < 220; k++) {
            float r2 = dot(x, x);
            float r = sqrt(r2);
            nearest = min(nearest, r);
            if (r < 1.0) { swallowed = true; break; }
            if (r > 10.5 && dot(x, v) > 0.0) break;
            if (transmit < 0.01) break;
            float dt = clamp(0.09 * r, 0.02, 0.8);
            v += -1.5 * h2 * x / (r2 * r2 * r) * dt;
            vec3 next = x + v * dt;
            // The scene's depth is along the straight line from the eye, so it stops only a ray still close to that
            // line: one bent hard round the hole has gone somewhere else, and carries on. The last step is trimmed to
            // where the scene is, so the cut is smooth.
            float stepLength = length(next - x);
            bool stops = false;
            if (travelled + stepLength > budget && dot(normalize(v), dir) > 0.97) {
                next = mix(x, next, clamp((budget - travelled) / stepLength, 0.0, 1.0));
                stops = true;
            }
            travelled += stepLength;
            if (x.y * next.y < 0.0) {
                vec3 hit = mix(x, next, x.y / (x.y - next.y));
                float rr = length(hit.xz);
                if (rr > DISK_IN && rr < DISK_OUT) {
                    float a = atan(hit.z, hit.x);
                    // Kepler: inner orbits turn faster. Two copies a half-cycle apart, each restarted while the other
                    // shows, so the shear never winds the streaks into stripes.
                    float omega = 9.0 / (rr * sqrt(rr));
                    float streak = mix(bh_streaks(a - omega * phaseB * cycle, rr), bh_streaks(a - omega * phaseA * cycle, rr), weightA);
                    float temperature = pow(DISK_IN / rr, 0.75) * pow(max(1.0 - sqrt(DISK_IN / rr), 0.0), 0.25) * 2.05;
                    // Seen from here: Doppler-shifted by the orbit, redshifted climbing out of the well.
                    float beta = sqrt(0.5 / (rr - 1.0));
                    vec3 orbit = normalize(vec3(-hit.z, 0.0, hit.x));
                    float toward = dot(orbit, -normalize(v));
                    float g = sqrt(1.0 - 1.0 / rr) * sqrt(1.0 - beta * beta) / (1.0 - beta * toward);
                    g = mix(1.0, g, 0.85);
                    float density = smoothstep(DISK_IN, DISK_IN + 0.6, rr) * (1.0 - smoothstep(DISK_OUT - 2.0, DISK_OUT, rr))
                            * (0.08 + 1.5 * pow(streak, 1.6));
                    // The colour reads the temperature squared: its fall from 1 at the inner disk to 0.7 at the edge
                    // would otherwise span only gold to white.
                    vec3 emission = bh_blackbody(temperature * temperature * g) * pow(g, 3.0) * (0.15 + 1.2 * temperature) * density;
                    light += transmit * emission * 0.9;
                    transmit *= 1.0 - clamp(density * 0.7, 0.0, 0.9);
                }
            }
            x = next;
            if (stops) { blocked = true; break; }
        }
        // The photon ring: rays that skimmed the photon sphere at 1.5 before escaping.
        float alpha;
        if (swallowed) {
            alpha = 1.0;
        } else if (blocked) {
            alpha = 1.0 - transmit;
        } else {
            light += transmit * vec3(1.6, 1.3, 1.0) * exp(-(nearest - 1.5) * 9.0) * 0.35;
            // The stars behind, bent: shown where the ray turned enough that the unbent scene would be wrong.
            vec3 outWorld = normalize(model * normalize(v));
            float deflection = 1.0 - dot(outWorld, inWorld);
            float lensed = smoothstep(0.06, 0.5, deflection);
            light += transmit * lensed * bh_sky(outWorld);
            alpha = 1.0 - transmit * (1.0 - lensed);
        }
        fragColor = vec4(fx_aces(0.8 * light), clamp(alpha, 0.0, 1.0));
    }
}
