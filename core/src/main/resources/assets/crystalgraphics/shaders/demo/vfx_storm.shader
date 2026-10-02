// A plasma globe: a dark glass ball filled with a faint violet haze, a glowing glass electrode at its heart, and
// electric tendrils snaking from it to the glass, white-blue going violet, each drifting and flickering on its own
// rhythm and surging as it re-strikes, flaring where it touches. The tendrils are drawn in the plane through the
// centre facing the camera, as a radial pattern seen from any side: one pointing at you is short, and one leaning
// away passes behind the electrode. From inside the globe they are drawn in 3D round you. CgVfxShowcase.
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
        Cull OFF
    }

    // A tendril's sideways wander at {@code s} along it: smooth noise flowing over time, plus a fine flicker.
    float storm_wander(float s, float seed, float t) {
        return (fx_value_noise(vec3(s * 3.0, seed, t * 1.3)) - 0.5) * 0.7
             + (fx_value_noise(vec3(s * 9.0, seed + 5.0, t * 4.0)) - 0.5) * 0.25;
    }

    // Where a tendril from {@code a} along {@code dir} for {@code len} is, {@code s} along it. It leaves both ends
    // along {@code dir}, so the distance across it meets the distance to an end with no seam.
    vec2 storm_point(vec2 a, vec2 dir, float len, float s, float seed, float t) {
        float envelope = pow(max(sin(s * 3.14159), 0.0), 1.5);
        return a + dir * (s * len) + vec2(-dir.y, dir.x) * (storm_wander(s, seed, t) * len * 0.35 * envelope);
    }

    // How bright one tendril is at {@code q}: a thin bright core in a soft sheath, thinning toward its tip. Past either
    // end the distance is to the end itself, so the glow rounds off there rather than stopping in a straight edge.
    float storm_tendril(vec2 q, vec2 a, vec2 dir, float len, float seed, float t, float width) {
        float s = dot(q - a, dir) / len;
        float along = clamp(s, 0.0, 1.0);
        vec2 p = storm_point(a, dir, len, along, seed, t);
        float d;
        if (s > 0.0 && s < 1.0) {
            vec2 tangent = normalize(storm_point(a, dir, len, s + 0.01, seed, t) - storm_point(a, dir, len, s - 0.01, seed, t));
            d = abs(dot(q - p, vec2(-tangent.y, tangent.x)));
        } else {
            d = length(q - p);
        }
        float w = width * (1.0 - 0.5 * along);
        return (exp(-d / w) + exp(-d / (w * 5.0)) * 0.35 + exp(-d / (w * 18.0)) * 0.12) * (1.0 - 0.3 * along);
    }

    // Tendril {@code seed} at {@code t}: the point on the glass it reaches for (a unit direction), and in the outs how
    // hard it is surging, its flicker and its own clock.
    vec3 storm_state(float seed, float t, out float surge, out float flicker, out float wild) {
        float h = fx_hash31(vec3(seed, 6.0, 6.0));
        vec3 base = normalize(fx_hash33(vec3(seed, 1.0, 2.0)) - 0.5);
        float pace = 0.5 + 1.2 * h;
        vec3 drift = vec3(sin(t * 0.37 * pace + seed * 1.7), sin(t * 0.29 * pace + seed * 2.3), sin(t * 0.33 * pace + seed * 0.9));
        float strikes = t * (1.5 + 3.5 * fx_hash31(vec3(seed, 7.0, 3.0))) + seed * 0.37;
        surge = exp(-fract(strikes) * (4.0 + 6.0 * h)) * step(0.35, fx_hash31(vec3(floor(strikes), seed, 4.0)));
        flicker = (0.25 + 0.75 * fx_value_noise(vec3(t * (6.0 + 14.0 * h), seed, 3.0))) * (0.45 + 1.6 * surge);
        wild = t * (1.0 + 2.0 * h) + surge * 0.6;
        return normalize(base + drift * 0.5);
    }

    // Inside the globe: how bright a tendril from {@code a} to {@code b} (world space) is along the ray from
    // {@code eye} along {@code d}, wandering sideways as it goes; in y how far along it the nearest point is.
    vec2 storm_tendril3d(vec3 eye, vec3 d, vec3 a, vec3 b, float seed, float t, float width) {
        vec3 e = b - a;
        vec3 w0 = eye - a;
        float de = dot(d, e);
        float u = clamp((dot(e, w0) - de * dot(d, w0)) / max(dot(e, e) - de * de, 1.0e-6), 0.0, 1.0);
        vec3 side = normalize(cross(e, d) + vec3(1.0e-5));
        float envelope = pow(max(sin(u * 3.14159), 0.0), 1.5);
        vec3 p = a + e * u + side * (storm_wander(u, seed, t) * length(e) * 0.35 * envelope);
        vec3 to = p - eye;
        float along = dot(to, d);
        if (along < 0.0) return vec2(0.0, u);
        float dist = length(to - d * along);
        float w = width * (1.0 - 0.5 * u);
        return vec2((exp(-dist / w) + exp(-dist / (w * 5.0)) * 0.35 + exp(-dist / (w * 18.0)) * 0.12) * (1.0 - 0.3 * u), u);
    }

    // The electrode where the ray meets it: {@code ballNormal} its normal there, {@code z} how squarely (1 at its middle).
    vec3 storm_electrode(vec3 ballNormal, float z, float pulse) {
        float glow = 0.6 + 0.4 * z;
        float highlight = pow(max(dot(ballNormal, normalize(vec3(-0.45, 0.55, 0.7))), 0.0), 40.0);
        return (vec3(0.8, 0.85, 1.6) * glow + vec3(1.2, 0.4, 1.5) * pow(1.0 - z, 3.0) * 1.5) * 2.2 * pulse
                + vec3(1.0) * highlight * 3.0;
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
        vec3 n = normalize(i.normalWs);
        if (!gl_FrontFacing) n = -n;
        vec3 camera = FX_CAMERA;
        vec3 v = normalize(camera - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        float pulse = 0.85 + 0.15 * sin(t * 9.0) + 0.1 * fx_value_noise(vec3(t * 13.0, 0.0, 0.0));
        vec3 inside;
        if (gl_FrontFacing) {
        // The plane through the centre facing the camera, in radii.
        vec3 ray = -v;
        vec3 offset = camera + ray * dot(centre - camera, ray) - centre;
        vec3 facing = normalize(centre - camera);
        vec3 right = normalize(cross(facing, vec3(0.0, 1.0, 0.0)));
        vec3 up = cross(right, facing);
        vec2 q = vec2(dot(offset, right), dot(offset, up)) / radius;
        // The tendrils: each from the electrode toward a point on the glass that drifts round at its own pace, each
        // flickering on its own rhythm and now and then surging bright as it re-strikes, then dying back.
        // Those leaning away from you pass behind the electrode and glow dimmer through the haze.
        float front = 0.0, back = 0.0, contact = 0.0, landing = 0.0;
        for (int k = 0; k < 15; k++) {
            float seed = float(k);
            float surge, flicker, wild;
            vec3 toward = storm_state(seed, t, surge, flicker, wild);
            vec2 flat2 = vec2(dot(toward, right), dot(toward, up));
            float reach = length(flat2);
            if (reach < 0.05) continue;
            vec2 dir = flat2 / reach;
            float len = 0.95 * reach - 0.14;
            float width = 0.009 + 0.008 * surge;
            float tendril = storm_tendril(q, dir * 0.14, dir, len, seed, wild, width);
            // Tips forking off toward the glass, more of them while it surges.
            for (int f = 0; f < 3; f++) {
                float at = 0.55 + 0.15 * float(f);
                vec2 fork = storm_point(dir * 0.14, dir, len, at, seed, wild);
                float turn = (f == 1 ? -1.0 : 1.0) * (0.35 + 0.25 * float(f)) + 0.25 * sin(t * 2.5 + seed + float(f));
                vec2 tipDir = vec2(dir.x * cos(turn) - dir.y * sin(turn), dir.x * sin(turn) + dir.y * cos(turn));
                tendril += storm_tendril(q, fork, tipDir, len * (0.18 + 0.1 * float(f)), seed + 9.0 + float(f), wild * 1.5, width * 0.55)
                        * (0.5 + 0.5 * surge);
            }
            if (dot(toward, facing) < 0.0) front += tendril * flicker;
            else back += tendril * flicker * 0.6;
            // Where it meets the glass it flares, and the glow spreads out across the glass round it.
            vec2 end = storm_point(dir * 0.14, dir, len, 1.0, seed, wild);
            float landed = dot(q - end, q - end);
            contact += exp(-landed / (0.003 + 0.004 * surge)) * flicker;
            landing += exp(-landed / 0.03) * flicker;
        }
        // The tendrils' colour runs from white-blue at the electrode to violet toward the glass.
        float r = length(q);
        vec3 electric = mix(vec3(0.5, 0.65, 1.7), vec3(1.1, 0.35, 1.6), smoothstep(0.2, 0.95, r));
        vec3 behind = electric * back * 2.2 + vec3(1.0, 0.95, 1.1) * pow(min(back, 1.0), 3.0);
        vec3 before = electric * front * 2.4 + vec3(1.0, 0.95, 1.1) * pow(min(front, 1.0), 3.0) * 1.5;
        // The electrode: a small glass ball lit from inside, shaded round, a highlight on it and a pulsing halo.
        float er = 0.13;
        float ball = 1.0 - smoothstep(er - 0.008, er, r);
        float z = sqrt(max(1.0 - (r * r) / (er * er), 0.0));
        vec3 ballNormal = vec3(q / er, z);
        vec3 electrode = storm_electrode(ballNormal, z, pulse);
        float halo = exp(-r * r / 0.035) * pulse;
        // A faint violet haze filling the globe, brighter where the tendrils land on the glass.
        vec3 haze = vec3(0.3, 0.12, 0.6) * (0.06 + 0.05 * (1.0 - r * r)) + vec3(0.9, 0.3, 1.4) * landing * 0.5;
        inside = vec3(0.008, 0.006, 0.02) + haze + electric * (contact * 2.0 + halo * 1.3);
        inside += behind * (1.0 - ball);
        inside = mix(inside, electrode, ball) + before;
        } else {
            // From inside the globe: the tendrils in the space round you, the electrode where the ray meets it, and the
            // glow where each tendril lands on the wall ahead.
            vec3 d = -v;
            vec3 onWall = normalize(i.worldPos - centre);
            vec3 tendrils = vec3(0.0);
            float contact = 0.0, landing = 0.0;
            for (int k = 0; k < 15; k++) {
                float seed = float(k);
                float surge, flicker, wild;
                vec3 toward = storm_state(seed, t, surge, flicker, wild);
                vec2 lit = storm_tendril3d(camera, d, centre + toward * (0.14 * radius), centre + toward * (0.95 * radius),
                        seed, wild, (0.009 + 0.008 * surge) * radius);
                vec3 electric = mix(vec3(0.5, 0.65, 1.7), vec3(1.1, 0.35, 1.6), smoothstep(0.1, 0.9, lit.y));
                tendrils += (electric * 2.4 + vec3(1.0, 0.95, 1.1) * pow(min(lit.x, 1.0), 2.0) * 1.5) * lit.x * flicker;
                float landed = dot(onWall - toward, onWall - toward);
                contact += exp(-landed / 0.004) * flicker;
                landing += exp(-landed / 0.04) * flicker;
            }
            // The electrode: where the ray meets the ball at the centre, and the halo round it.
            vec3 oc = camera - centre;
            float er = 0.13 * radius;
            float b = dot(oc, d);
            float disc = b * b - (dot(oc, oc) - er * er);
            float hitAt = -b - sqrt(max(disc, 0.0));
            float pass = length(oc - d * b) / radius;
            float halo = exp(-pass * pass / 0.035) * step(b, 0.0) * pulse;
            inside = vec3(0.008, 0.006, 0.02) + vec3(0.3, 0.12, 0.6) * 0.08 + vec3(0.9, 0.3, 1.4) * landing * 0.5
                    + vec3(0.8, 0.45, 1.6) * contact * 2.0 + vec3(0.5, 0.65, 1.7) * halo * 1.3 + tendrils;
            if (disc > 0.0 && hitAt > 0.0) {
                vec3 ballNormal = normalize(oc + d * hitAt);
                inside = storm_electrode(ballNormal, max(dot(ballNormal, -d), 0.0), pulse);
            }
        }
        // The glass: a dark mirror of the studio, faint facing you, strong at the rim; fainter still from inside.
        float fresnel = (0.04 + 0.96 * pow(1.0 - nv, 5.0)) * (gl_FrontFacing ? 1.0 : 0.25);
        vec3 reflection = vfx_studio(i.worldPos, reflect(-v, n), 0.02, floorY)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.03) * 2.0;
        vec3 color = inside * (1.0 - fresnel) + reflection * fresnel;
        fragColor = vec4(fx_aces(0.8 * color), 1.0);
    }
}
