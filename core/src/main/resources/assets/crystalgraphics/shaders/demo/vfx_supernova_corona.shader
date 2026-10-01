// The supernova's corona, drawn on a sphere beyond its heart and added over what lies behind it: a skirt of fire
// hugging the rim with spiky flares licking off it, in the heart's own colours; a thin orange-pink haze; embers flung
// off; and branching violet lightning leaping from the face out into space. Every fragment works in the plane through
// the centre facing the camera, so the corona reads the same from any side, inside it or out; vfx_seen dims it by
// whatever of the scene stands inside it. CG_OBJECT_CUSTOM1: x the heart's radius over this sphere's, y the strength.
// CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_halo.glsl"
#include "crystalgraphics:shaders/demo/vfx_fire.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    // An angle wrapped into [-pi, pi].
    float corona_wrap(float a) {
        return mod(a + 3.14159265, 6.2831853) - 3.14159265;
    }

    // Embers in one ring of angular slots: each slot flings a spark outward on its own clock. {@code r} in heart radii.
    float corona_embers(float angle, float r, float t, float slots, float seed) {
        float slot = floor((angle + 3.14159265) / 6.2831853 * slots);
        float h = vfx_hash31(vec3(slot, seed, 1.0));
        float centreAngle = (slot + 0.2 + 0.6 * vfx_hash31(vec3(slot, seed, 2.0))) / slots * 6.2831853 - 3.14159265;
        float h2 = vfx_hash31(vec3(slot, seed, 3.0));
        float travel = fract(t * (0.12 + 0.3 * h2) + h * 7.0);
        float sparkR = 0.95 + 0.35 * h2 + travel * (0.8 + 0.8 * h);
        float across = corona_wrap(angle - centreAngle) * r;
        float d2 = across * across + (r - sparkR) * (r - sparkR);
        float size = 0.0003 + 0.0006 * h2;
        return exp(-d2 / size) * (1.0 - travel) * step(0.45, h);
    }

    // A bolt's sideways wander at {@code s} along it: noise that is straight between its knots, summed over octaves,
    // so it kinks sharply at every scale as lightning does.
    float bolt_wander(float s, float seed) {
        float w = 0.0, amp = 0.5, freq = 4.0;
        for (int k = 0; k < 4; k++) {
            float x = s * freq + seed * 13.1;
            float knot = floor(x);
            float a = vfx_hash31(vec3(knot, seed, float(k)));
            float b = vfx_hash31(vec3(knot + 1.0, seed, float(k)));
            w += (mix(a, b, fract(x)) - 0.5) * amp;
            amp *= 0.5;
            freq *= 2.3;
        }
        return w;
    }

    // Where a bolt from {@code a} along {@code dir} for {@code len} is, {@code s} along it: pinned at its root, free
    // at its tip.
    vec2 bolt_point(vec2 a, vec2 dir, float len, float s, float seed) {
        return a + dir * (s * len) + vec2(-dir.y, dir.x) * (bolt_wander(s, seed) * len * 0.4 * min(s * 5.0, 1.0));
    }

    // How bright one bolt is at {@code q}: a thin white-hot core in a softer sheath, thinning toward its tip.
    float bolt_light(vec2 q, vec2 a, vec2 dir, float len, float seed, float width) {
        float s = dot(q - a, dir) / len;
        if (s < -0.05 || s > 1.05) return 0.0;
        float e = 0.01;
        vec2 p = bolt_point(a, dir, len, s, seed);
        vec2 slope = bolt_point(a, dir, len, s + e, seed) - bolt_point(a, dir, len, s - e, seed);
        // The distance across the path, not along the bolt's axis, so a steep kink is as thick as a straight run.
        vec2 tangent = normalize(slope);
        float d = abs(dot(q - p, vec2(-tangent.y, tangent.x)));
        float w = width * (1.0 - 0.6 * clamp(s, 0.0, 1.0));
        float ends = smoothstep(-0.05, 0.02, s) * (1.0 - smoothstep(0.9, 1.05, s));
        return (exp(-d / w) + exp(-d / (w * 12.0)) * 0.3) * ends;
    }

    // One lane of lightning: a bolt about twice a second from over the face out past the rim, forking twice,
    // strobing a few times through its short life.
    float corona_lightning(vec2 q, float t, float lane) {
        float clock = t * 2.1 + lane * 0.43;
        float strike = floor(clock);
        float life = fract(clock);
        if (vfx_hash31(vec3(strike, lane, 11.0)) < 0.3) return 0.0;
        float root = vfx_hash31(vec3(strike, lane, 12.0)) * 6.2831853;
        vec2 a = vec2(cos(root), sin(root)) * (0.45 + 0.45 * vfx_hash31(vec3(strike, lane, 13.0)));
        float heading = root + (vfx_hash31(vec3(strike, lane, 14.0)) - 0.5) * 1.1;
        vec2 dir = vec2(cos(heading), sin(heading));
        float len = 1.1 + 0.9 * vfx_hash31(vec3(strike, lane, 15.0));
        float seed = strike * 7.31 + lane * 3.7;
        float light = bolt_light(q, a, dir, len, seed, 0.011);
        for (int k = 0; k < 2; k++) {
            float at = 0.25 + 0.45 * vfx_hash31(vec3(strike, lane, 20.0 + float(k)));
            vec2 fork = bolt_point(a, dir, len, at, seed);
            float turn = (k == 0 ? 1.0 : -1.0) * (0.35 + 0.5 * vfx_hash31(vec3(strike, lane, 22.0 + float(k))));
            vec2 forkDir = vec2(dir.x * cos(turn) - dir.y * sin(turn), dir.x * sin(turn) + dir.y * cos(turn));
            float forkLen = len * (0.22 + 0.3 * vfx_hash31(vec3(strike, lane, 24.0 + float(k))));
            light += bolt_light(q, fork, forkDir, forkLen, seed + 31.0 + float(k), 0.007) * 0.8;
        }
        float strobe = step(0.3, vfx_hash31(vec3(floor(t * 28.0), lane, strike)));
        return light * strobe * (1.0 - smoothstep(0.5, 0.75, life));
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float shell = length(CG_OBJECT_TO_WORLD[0].xyz);
        float heart = shell * CG_OBJECT_CUSTOM1.x;
        vec3 camera = VFX_CAMERA;
        vec3 ray = normalize(i.worldPos - camera);
        vec2 pass = vfx_pass_by(camera, ray, centre);
        // The plane through the centre facing the camera, in heart radii.
        vec3 offset = camera + ray * pass.x - centre;
        vec3 facing = normalize(centre - camera);
        vec3 right = normalize(cross(facing, vec3(0.0, 1.0, 0.0)));
        vec3 up = cross(right, facing);
        vec2 q = vec2(dot(offset, right), dot(offset, up)) / heart;
        float r = length(q);
        float angle = atan(q.y, q.x);
        vec2 around = q / max(r, 1.0e-4);
        float outward = max(r - 1.0, 0.0);
        float scene = VFX_SCENE_DISTANCE(ray);

        // The skirt: a thin layer of fire on the rim, churning outward.
        float churn = vfx_fbm(vec3(around * 6.0, r * 3.0 - t * 1.2), 4);
        float skirt = exp(-outward * 14.0) * (0.3 + 0.6 * churn);
        // Tongues: big licks of flame, each reaching its own way out and burning out toward its end.
        float tongueReach = 0.12 + 0.6 * pow(vfx_fbm(vec3(around * 2.2, t * 0.4), 3), 1.5);
        float body = vfx_fbm(vec3(around * 5.0, r * 2.5 - t * 1.3), 4);
        float tongue = (1.0 - smoothstep(0.0, tongueReach, outward)) * (0.3 + 0.8 * body)
                * (1.0 - 0.7 * outward / tongueReach);
        // Spikes: thin sharp flares between them.
        float streak = pow(vfx_ridged(vec3(around * 11.0, r * 1.2 - t * 1.6), 3), 2.0);
        float spikeReach = 0.1 + 0.45 * pow(vfx_noise(vec3(around * 6.0, t * 0.7)), 2.0);
        float spike = streak * (1.0 - smoothstep(0.0, spikeReach, outward)) * 0.9;
        float heat = clamp(max(skirt, max(tongue, spike)), 0.0, 1.0);
        vec3 color = vfx_fire(heat) * smoothstep(0.03, 0.3, heat) * 1.2;
        // A thin haze beyond, orange going pink.
        color += mix(vec3(1.4, 0.55, 0.2), vec3(1.0, 0.35, 0.6), smoothstep(0.2, 1.0, outward)) * exp(-outward * 1.8) * 0.2;
        // Embers flung off, red cooling to dark as they fly.
        float embers = corona_embers(angle, r, t, 52.0, 1.0) + corona_embers(angle + 0.05, r, t * 1.13, 37.0, 2.0);
        color += vec3(2.4, 0.3, 0.04) * embers * 2.2;
        // All that is light in the volume: the scene in front hides it, and it thins over the heart's face.
        color *= vfx_seen(pass.x, sqrt(max(pass.y, heart) * heart), scene) * vfx_limb(pass.y, heart);
        // Lightning sits at the heart's front, so it crosses the face as it leaps out.
        float bolts = corona_lightning(q, t, 0.0) + corona_lightning(q, t, 1.0) + corona_lightning(q, t, 2.0);
        bolts *= vfx_seen(pass.x - heart, heart * 0.25, scene);
        color += vec3(1.3, 0.4, 2.6) * bolts * 2.6 + vec3(1.0, 0.85, 1.0) * pow(min(bolts, 1.0), 4.0) * 1.6;
        // Nothing at the shell's edge, nothing from inside the heart.
        float edge = 1.0 - smoothstep(0.85, 1.0, pass.y / shell);
        float inside = smoothstep(heart * 0.95, heart * 1.3, length(camera - centre));
        fragColor = vec4(color * CG_OBJECT_CUSTOM1.y * edge * inside, 1.0);
    }
}
