// An editor's sky, as Roblox Studio's: deep blue overhead to a pale horizon, a soft white sun, and a hazy blue-grey
// below the horizon. _Night blends to a night sky: blue-black overhead over a faint glow along the horizon, three
// layers of twinkling stars, the Milky Way's band and a moon. Draw it on a sphere round the camera in
// CgSortLayer.BACKGROUND; it sits at the far plane, so everything draws in front of it.
#type spatial

Tags { "RenderType" = "Background" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Background"

Properties {
    _SunDirection ("Toward the sun, any length", vec4) = (0.45, 0.55, -0.7, 0.0)
    _Night ("0 day, 1 night; between, a blend", float) = 0.0
    _MoonDirection ("Toward the moon, any length", vec4) = (-0.55, 0.42, -0.72, 0.0)
    _BandNormal ("Normal of the Milky Way's plane, any length", vec4) = (0.35, 0.55, 0.76, 0.0)
}

struct v2f { vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull FRONT
    }

    // A Pass's helpers compile into the vertex stage too, which has no derivatives: the fragment passes in the angle
    // a pixel spans.

    float night_hash(vec3 p) {
        p = fract(p * vec3(443.897, 441.423, 437.195));
        p += dot(p, p.yzx + 19.19);
        return fract((p.x + p.y) * p.z);
    }

    float night_noise(vec3 p) {
        vec3 c = floor(p), f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        return mix(mix(mix(night_hash(c), night_hash(c + vec3(1, 0, 0)), f.x),
                       mix(night_hash(c + vec3(0, 1, 0)), night_hash(c + vec3(1, 1, 0)), f.x), f.y),
                   mix(mix(night_hash(c + vec3(0, 0, 1)), night_hash(c + vec3(1, 0, 1)), f.x),
                       mix(night_hash(c + vec3(0, 1, 1)), night_hash(c + vec3(1, 1, 1)), f.x), f.y), f.z);
    }

    float night_fbm(vec3 p) {
        float sum = 0.0, amp = 0.5;
        for (int i = 0; i < 4; i++) {
            sum += night_noise(p) * amp;
            p = p * 2.03 + 7.1;
            amp *= 0.5;
        }
        return sum;
    }

    // One layer of stars: one in each cell of a lattice `scale` cells to the unit radius, kept with chance `density`,
    // `peak` its brightest. Each is a point a pixel or two across, so it stays sharp at any field of view.
    vec3 night_stars(vec3 d, float scale, float density, float peak, float pixel, float time) {
        vec3 p = d * scale;
        vec3 base = floor(p - 0.5);
        vec3 light = vec3(0.0);
        // The 2x2x2 cells nearest p hold every star within half a cell of it.
        for (int i = 0; i < 8; i++) {
            vec3 cell = base + vec3(float(i % 2), float((i / 2) % 2), float(i / 4));
            if (night_hash(cell + 3.7) > density) continue;
            vec3 star = normalize(cell + 0.2 + 0.6 * vec3(night_hash(cell), night_hash(cell + 17.3), night_hash(cell + 41.9)));
            float h = night_hash(cell + 91.1);
            // Most faint, a few bright: brightness, never size, tells them apart.
            float magnitude = 0.12 + 0.88 * pow(h, 8.0);
            float px = length(d - star) / pixel;
            // A point a pixel across, and round the brightest a small soft halo.
            float core = clamp(1.25 - px, 0.0, 1.0);
            float halo = exp(-px * px / 5.0) * 0.3 * magnitude * magnitude;
            float twinkle = 0.8 + 0.2 * sin(time * (1.3 + 3.0 * h) + h * 61.0);
            vec3 tint = mix(vec3(0.72, 0.82, 1.00), vec3(1.00, 0.88, 0.74), night_hash(cell + 7.7));
            light += tint * (core + halo) * magnitude * twinkle * peak;
        }
        return light;
    }

    vec3 night_sky(vec3 d, float pixel, float time) {
        float y = d.y;
        vec3 zenith = vec3(0.012, 0.018, 0.045);
        vec3 high = vec3(0.025, 0.040, 0.085);
        vec3 horizon = vec3(0.060, 0.095, 0.150);
        vec3 ground = vec3(0.010, 0.012, 0.018);
        if (y < 0.0) return mix(horizon * 0.55, ground, smoothstep(0.0, -0.25, y));
        vec3 sky = mix(horizon, high, smoothstep(0.0, 0.25, y));
        sky = mix(sky, zenith, smoothstep(0.25, 1.0, y));

        // The Milky Way: a band round the sky, mottled, split by dark dust lanes, denser with faint stars.
        float across = dot(d, normalize(_BandNormal.xyz));
        float band = exp(-across * across / 0.06);
        float glow = night_fbm(d * 4.0);
        float lanes = smoothstep(0.48, 0.68, night_fbm(d * 11.0 + 3.1));
        sky += vec3(0.100, 0.105, 0.150) * band * (0.25 + 1.1 * glow) * (1.0 - 0.7 * lanes);

        vec3 stars = night_stars(d, 45.0, 0.50, 1.00, pixel, time)
                + night_stars(d, 100.0, 0.40, 0.50, pixel, time)
                + night_stars(d, 220.0, 0.15 + 0.50 * band, 0.18, pixel, time);
        // Fewer and dimmer through the thicker air near the horizon.
        sky += stars * smoothstep(0.0, 0.3, y);

        // The moon: a disc darker at its limb and over its seas, under a tight halo and a wide one.
        vec3 m = normalize(_MoonDirection.xyz);
        float off = length(d - m);
        float r = min(off / 0.022, 1.0);
        float disc = smoothstep(0.022 + pixel, 0.022, off);
        float seas = smoothstep(0.50, 0.68, night_fbm((d - m) * 70.0 + 5.0));
        vec3 moon = vec3(0.92, 0.93, 0.97) * (0.78 + 0.22 * sqrt(1.0 - r * r)) * (1.0 - 0.18 * seas);
        float toward = max(dot(d, m), 0.0);
        sky = mix(sky, moon, disc);
        sky += vec3(0.55, 0.65, 0.85) * (pow(toward, 3000.0) * 0.25 + pow(toward, 200.0) * 0.08 + pow(toward, 20.0) * 0.03);
        return sky;
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        // World space is camera-relative: a vertex's position is its direction from the eye.
        o.dir = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
        // Just inside the far plane, in whichever depth convention the host draws.
        gl_Position.z = gl_Position.w * (cg_DepthParams.x > 0.5 ? (cg_DepthParams.y > 0.5 ? 1.0e-5 : -1.0 + 1.0e-5) : 1.0 - 1.0e-5);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 d = normalize(i.dir);
        float y = d.y;
        vec3 zenith = vec3(0.04, 0.22, 0.62);
        vec3 high = vec3(0.12, 0.42, 0.84);
        vec3 horizon = vec3(0.78, 0.89, 0.93);
        vec3 haze = vec3(0.62, 0.67, 0.74);
        vec3 ground = vec3(0.40, 0.44, 0.51);
        vec3 sky = mix(horizon, high, smoothstep(0.0, 0.3, y));
        sky = mix(sky, zenith, smoothstep(0.3, 1.0, y));
        vec3 below = mix(haze, ground, smoothstep(0.0, -0.25, y));
        vec3 color = y >= 0.0 ? sky : below;
        // The sun: a disc with a soft rim, a tight glow and a wide one, above the horizon only.
        float toward = max(dot(d, normalize(_SunDirection.xyz)), 0.0);
        float sun = smoothstep(0.9990, 0.9996, toward) + pow(toward, 400.0) * 0.6 + pow(toward, 24.0) * 0.12;
        color += vec3(1.0) * sun * smoothstep(-0.02, 0.02, y);
        if (_Night > 0.0) color = mix(color, night_sky(d, max(length(dFdx(d)), length(dFdy(d))), CG_TIME), _Night);
        fragColor = vec4(min(color, vec3(1.0)), 1.0);
    }
}
