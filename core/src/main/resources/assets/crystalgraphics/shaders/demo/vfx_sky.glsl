// The showcase's sky: deep space over the studio. A domain-warped nebula of magenta, teal and gold gas, coloured near
// each sun by its light and rimmed bright on the cloud edges facing it, with dark dust cut through; the Milky Way as a
// band of dense stars with a dark rift down it; stars that are round, coloured by temperature and never smaller than a
// pixel, the brightest with James Webb's six diffraction spikes; and the three suns where the studio's three lights
// are, each its own kind of star -- a white-gold star blazing with rays (the key), a blue giant inside a ring nebula
// (the rim) and a huge crimson giant boiling low on the horizon (the fill). A ringed gas giant turns between them,
// its bands blowing in opposite winds, two moons orbiting it, and now and then a shooting star crosses.
// vfx_sky.shader draws it behind everything; vfx_sky_horizon.shader blends it over a host's distant terrain.
#pragma once

#include "crystalgraphics:shaders/demo/vfx_common.glsl"

// Two unit vectors perpendicular to {@code n} and to each other.
void sky_frame(vec3 n, out vec3 u, out vec3 w) {
    u = normalize(cross(n, abs(n.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    w = cross(n, u);
}

// One layer of stars: cells over each cube face, a star in the share {@code 1 - keep} of them. Each is round,
// coloured by its temperature and never smaller than a pixel ({@code pixel} radians), dimming as it spreads; with
// {@code spikes}, the brightest carry six diffraction spikes.
vec3 sky_stars(vec3 d, float density, float keep, float pixel, float spikes, float seed, float t) {
    vec3 face = vfx_cube_face(d);
    vec2 g = (face.xy * 0.5 + 0.5) * density;
    vec2 cell = floor(g);
    float px = pixel * density * 0.64;
    vec3 light = vec3(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 c = cell + vec2(float(i), float(j));
            vec3 h = fx_hash33(vec3(c, face.z * 7.0 + seed));
            if (h.z < keep) continue;
            vec2 o = g - c - h.xy;
            float bright = pow((h.z - keep) / (1.0 - keep), 3.0);
            float size = 0.05 + 0.09 * bright;
            float radius = max(size, px * 0.6);
            float energy = (size / radius) * (size / radius);
            float temperature = fract(h.z * 31.7);
            vec3 tint = temperature < 0.3 ? mix(vec3(1.3, 0.55, 0.3), vec3(1.2, 0.95, 0.75), temperature / 0.3)
                      : mix(vec3(1.0, 0.98, 1.0), vec3(0.6, 0.78, 1.4), (temperature - 0.3) / 0.7);
            float twinkle = 0.7 + 0.3 * sin(t * (2.0 + 6.0 * h.x) + h.y * 40.0) * sin(t * (1.3 + 3.0 * h.y) + h.x * 20.0);
            light += tint * exp(-dot(o, o) / (radius * radius)) * energy * (0.35 + 4.0 * bright) * twinkle;
            if (spikes > 0.0 && bright > 0.45) {
                float glare = 0.0;
                for (int a = 0; a < 3; a++) {
                    float angle = float(a) * 1.0471976 + 0.3;
                    vec2 axis = vec2(cos(angle), sin(angle));
                    float along = abs(dot(o, axis));
                    float across = abs(dot(o, vec2(-axis.y, axis.x)));
                    glare += exp(-across / max(px * 0.5, 0.012)) * exp(-along / (spikes * bright));
                }
                light += tint * glare * bright * 0.5 * twinkle;
            }
        }
    }
    return light;
}

// The nebula's density at {@code q}, and its warp, which evolves so the clouds billow and flow.
float sky_cloud(sampler3D noise, vec3 q, float t, out vec3 warp) {
    vec3 flow = vec3(t * 0.045, -t * 0.03, t * 0.038);
    warp = vec3(cg_fbm3(noise, q * 1.3 + vec3(1.7, 9.2, 0.0) + flow, 4), cg_fbm3(noise, q * 1.3 + vec3(8.3, 2.8, 4.1) - flow, 4),
                cg_fbm3(noise, q * 1.3 + vec3(3.0, 6.0, 1.0) + flow.zxy, 4));
    return cg_fbm3(noise, q + warp * 1.8, 6);
}

// A sun's glow and corona at angular distance {@code a} (radians) from its centre: tight, then wide.
float sky_glow(float a, float tight, float wide) {
    return exp(-a / tight) + exp(-a / wide) * 0.25;
}

// A flow cycle at {@code t}, Vlachos' flow map (Valve, 2010): two phases a half-cycle apart, each displacing a
// texture for at most {@code cycle} seconds before it restarts unseen, so a sheared flow never winds up. x and y the
// two phases' elapsed seconds, z the weight of the first.
vec3 sky_flow(float t, float cycle) {
    float a = fract(t / cycle), b = fract(t / cycle + 0.5);
    return vec3(a * cycle, b * cycle, 1.0 - abs(2.0 * a - 1.0));
}

// Moon {@code k} of the gas giant at {@code t}: its centre, and its radius in {@code radius}. Both orbit just beyond
// the rings, a little inclined to them, the inner faster.
vec3 sky_moon(int k, float t, vec3 planetDir, float planetSize, vec3 ringU, vec3 ringW, vec3 spin, out float radius) {
    float orbit = (k == 0 ? 2.8 : 3.7) * planetSize;
    float phase = t * (k == 0 ? 0.3 : 0.19) + float(k) * 2.4;
    radius = planetSize * (k == 0 ? 0.16 : 0.1);
    float inclined = k == 0 ? 0.06 : -0.1;
    return planetDir + (ringU * cos(phase) + ringW * sin(phase) + spin * sin(phase) * inclined) * orbit;
}

// The clip z of a vertex with clip w {@code w}, just inside the far plane whichever way the pass's depth runs: 1, or 0
// or -1 when reversed (Minecraft 26.2). A macro, since this file is compiled ahead of the frame block.
// Whether a raw depth {@code depth} is the far plane's: open sky, which no host draw has covered.
#define VFX_OPEN_SKY(depth) (CG_DEPTH_REVERSED ? (depth) <= 1.0e-6 : (depth) >= 1.0 - 1.0e-6)

#define VFX_SKY_FAR_Z(w) ((w) * (cg_DepthParams.x > 0.5 ? (cg_DepthParams.y > 0.5 ? 1.0e-5 : -1.0 + 1.0e-5) : 1.0 - 1.0e-5))

// The sky toward {@code d}, a unit vector, at {@code t} seconds, a pixel {@code pixel} radians across; tone mapped.
vec3 vfx_sky(sampler3D noise, vec3 d, float t, float pixel) {
    const vec3 KEY_COLOR = vec3(3.0, 2.55, 1.8);
    const vec3 RIM_COLOR = vec3(1.2, 1.7, 3.2);
    const vec3 FILL_COLOR = vec3(2.4, 0.35, 0.55);
    float toKey = acos(clamp(dot(d, VFX_KEY_DIR), -1.0, 1.0));
    float toRim = acos(clamp(dot(d, VFX_RIM_DIR), -1.0, 1.0));
    float toFill = acos(clamp(dot(d, VFX_FILL_DIR), -1.0, 1.0));

    // Space: near black, deepest overhead, a violet glow toward the horizon.
    vec3 color = mix(vec3(0.03, 0.012, 0.06), vec3(0.004, 0.004, 0.014), smoothstep(0.0, 0.7, d.y));

    // The nebula: warped clouds, coloured by their own gas and by the suns near them, rimmed where they face them.
    vec3 q = d * 2.2 + vec3(t * 0.012, 0.0, t * 0.008);
    vec3 warp;
    float cloud = sky_cloud(noise, q, t, warp);
    vec3 gas = mix(vec3(1.0, 0.12, 0.7), vec3(0.08, 0.6, 0.85), smoothstep(0.35, 0.65, warp.x));
    gas = mix(gas, vec3(1.2, 0.6, 0.15), smoothstep(0.55, 0.75, warp.y) * 0.7);
    vec3 lit = KEY_COLOR * exp(-toKey / 0.7) * 0.35 + RIM_COLOR * exp(-toRim / 0.6) * 0.35
            + FILL_COLOR * exp(-toFill / 0.8) * 0.45 + vec3(0.25);
    float body = pow(smoothstep(0.32, 0.85, cloud), 1.6);
    vec3 light = normalize(VFX_KEY_DIR * exp(-toKey / 0.5) + VFX_RIM_DIR * exp(-toRim / 0.5)
            + VFX_FILL_DIR * exp(-toFill / 0.5) + d * 1.0e-4);
    float toward = cg_fbm3(noise, q + (light - d) * 0.12 + warp * 1.8, 6);
    float rim = clamp((cloud - toward) * 9.0, 0.0, 1.0) * smoothstep(0.35, 0.6, cloud);
    color += gas * lit * body * 0.9 + lit * gas * rim * 0.9;

    // The Milky Way: a band of haze and dense stars across the sky, a dark rift down it.
    vec3 bandNormal = normalize(vec3(0.35, 0.8, -0.45));
    float across = dot(d, bandNormal);
    float band = exp(-across * across / 0.05);
    float haze = cg_fbm3(noise, d * 7.0 + vec3(4.0), 5);
    color += vec3(0.75, 0.7, 0.9) * band * (0.04 + 0.12 * haze);

    // Dust: dark lanes through the nebula and the rift down the band, hiding what lies behind.
    float dust = smoothstep(0.55, 0.75, cg_fbm3(noise, q * 2.1 + warp * 2.5 + vec3(11.0), 5));
    float rift = exp(-pow(across / 0.035 + (haze - 0.5) * 2.5, 2.0)) * band;
    float hidden = clamp(dust * 0.8 + rift * 0.85, 0.0, 0.95);

    // Stars: a fine dense layer, thickest in the band, a middle one, and a few bright ones with spikes.
    vec3 stars = sky_stars(d, 260.0, 0.75 - 0.35 * band, pixel, 0.0, 1.0, t) * (0.5 + band)
            + sky_stars(d, 70.0, 0.88, pixel, 0.0, 2.0, t)
            + sky_stars(d, 16.0, 0.82, pixel, 0.55, 3.0, t);
    color = (color + stars) * (1.0 - hidden);

    // The key: a white-gold star, blazing, with a starburst of rays turning slowly.
    vec3 u, w;
    sky_frame(VFX_KEY_DIR, u, w);
    float phi = atan(dot(d, w), dot(d, u));
    float shimmer = toKey < 1.2 ? 0.55 + 0.45 * cg_noise3(noise, vec3(cos(phi) * 6.0, sin(phi) * 6.0, toKey * 6.0 - t * 1.6)) : 0.0;
    float rays = (pow(0.5 + 0.5 * cos(phi * 8.0 + t * 0.18), 90.0) * exp(-toKey / 0.4)
            + pow(0.5 + 0.5 * cos(phi * 23.0 - t * 0.27), 140.0) * exp(-toKey / 0.22) * 0.7) * shimmer
            * (0.85 + 0.15 * sin(t * 2.3));
    float keyDisk = 1.0 - smoothstep(0.035 - pixel, 0.035 + pixel, toKey);
    color += KEY_COLOR * (sky_glow(toKey, 0.03, 0.22) * 1.2 + rays * 0.8) + vec3(3.0) * keyDisk;

    // The rim: a blue giant inside a ring nebula, the ring broken and glowing, slowly breathing.
    sky_frame(VFX_RIM_DIR, u, w);
    float ringPhi = atan(dot(d, w), dot(d, u));
    float ringRadius = 0.15 + 0.006 * sin(t * 0.7);
    float ringNoise = toRim < 0.35
            ? cg_fbm3(noise, vec3(cos(ringPhi + t * 0.25) * 3.0, sin(ringPhi + t * 0.25) * 3.0, toRim * 20.0 - t * 0.2), 4) : 0.0;
    float ring = exp(-pow((toRim - ringRadius) / (0.012 + 0.012 * ringNoise), 2.0)) * (0.4 + 0.9 * ringNoise);
    float rimDisk = 1.0 - smoothstep(0.022 - pixel, 0.022 + pixel, toRim);
    color += RIM_COLOR * sky_glow(toRim, 0.025, 0.18) * 0.9 + vec3(0.3, 0.85, 1.6) * ring * 1.2
            + vec3(1.0, 0.25, 0.9) * exp(-pow((toRim - ringRadius * 1.25) / 0.03, 2.0)) * ringNoise * 0.4
            + vec3(2.6, 3.0, 3.6) * rimDisk;

    // The fill: a huge crimson giant low on the horizon, its surface boiling, prominences arching off its edge.
    float giant = 0.14;
    sky_frame(VFX_FILL_DIR, u, w);
    vec2 onDisk = vec2(dot(d, u), dot(d, w)) / giant;
    float inside = 1.0 - smoothstep(1.0 - pixel / giant * 2.0, 1.0, toFill / giant);
    float limb = sqrt(max(1.0 - dot(onDisk, onDisk), 0.0));
    float boil = inside > 0.0 ? cg_fbm3(noise, vec3(onDisk * 5.0 + cg_fbm3(noise, vec3(onDisk * 2.0, t * 0.15), 3), t * 0.35), 5) : 0.0;
    vec3 surface = mix(vec3(1.6, 0.12, 0.2), vec3(3.2, 0.9, 0.55), boil) * (0.35 + 0.65 * pow(limb, 0.6));
    float fillPhi = atan(onDisk.y, onDisk.x);
    float prominence = toFill > giant * 1.6 ? 0.0 : smoothstep(0.55, 0.85, cg_value_ridged3(noise, vec3(cos(fillPhi) * 4.0, sin(fillPhi) * 4.0, toFill / giant * 3.0 - t * 0.35), 4))
            * exp(-max(toFill / giant - 1.0, 0.0) * 9.0) * step(1.0, toFill / giant);
    color = mix(color, surface, inside);
    color += FILL_COLOR * (sky_glow(max(toFill - giant, 0.0), 0.05, 0.45) * 0.8 + prominence * 1.2) * (1.0 - inside);

    // The gas giant: banded, lit by the key sun, its rings round it shadowed by it, two moons orbiting it.
    vec3 planetDir = normalize(vec3(0.62, 0.38, 0.69));
    float planetSize = 0.16;
    vec3 spin = normalize(vec3(0.25, 1.0, -0.35));
    vec3 ringU, ringW;
    sky_frame(spin, ringU, ringW);
    float b = dot(d, planetDir);
    float disc = b * b - (1.0 - planetSize * planetSize);
    float planetT = disc > 0.0 ? b - sqrt(disc) : 1.0e9;
    float ringT = dot(planetDir, spin) / dot(d, spin);
    vec3 ringPoint = d * ringT - planetDir;
    float ringR = length(ringPoint) / planetSize;
    float ringHere = ringT > 0.0 && ringR > 1.35 && ringR < 2.35 ? 1.0 : 0.0;
    float ringBands = (0.45 + 0.55 * cg_noise3(noise, vec3(ringR * 30.0, 1.0, 2.0))) * smoothstep(1.35, 1.45, ringR)
            * (1.0 - smoothstep(2.25, 2.35, ringR)) * (1.0 - smoothstep(1.86, 1.88, ringR) * (1.0 - smoothstep(1.92, 1.94, ringR)));
    // The planet's shadow on the rings, softened at its edge: how near the ray toward the key sun passes the planet.
    float sb = dot(ringPoint, VFX_KEY_DIR);
    float pass = sqrt(max(dot(ringPoint, ringPoint) - sb * sb, 0.0)) / planetSize;
    float ringShadow = sb < 0.0 ? mix(0.55, 1.0, smoothstep(0.85, 1.08, pass)) : 1.0;
    // The rings orbit, inner faster than outer as Kepler has it, grainy with clumps; dark spokes sweep round them
    // at the planet's own turn, as Saturn's do.
    float ringAngle = atan(dot(ringPoint, ringW), dot(ringPoint, ringU));
    float grain = 1.0;
    if (ringHere > 0.0) {
        float speed = 0.5 / (ringR * sqrt(ringR));
        vec3 flow = sky_flow(t, 6.0);
        float orbitA = ringAngle + speed * flow.x, orbitB = ringAngle + speed * flow.y + 1.7;
        grain = 0.5 + 0.5 * mix(cg_fbm3(noise, vec3(cos(orbitB) * 9.0, sin(orbitB) * 9.0, ringR * 22.0), 3),
                                cg_fbm3(noise, vec3(cos(orbitA) * 9.0, sin(orbitA) * 9.0, ringR * 22.0), 3), flow.z);
    }
    float spokeAngle = ringAngle + t * 0.12;
    float spokes = 1.0 - 0.45 * pow(0.5 + 0.5 * cos(spokeAngle * 7.0 + sin(spokeAngle * 3.0) * 1.5), 12.0)
            * smoothstep(1.5, 1.7, ringR) * (1.0 - smoothstep(2.0, 2.2, ringR));
    vec3 ringColor = vec3(1.25, 1.0, 0.75) * ringBands * ringShadow * grain * spokes * 0.62;
    // The moons: the nearer one the ray meets, and its lit face.
    float moonT = 1.0e9;
    vec3 moonColor = vec3(0.0);
    for (int k = 0; k < 2; k++) {
        float moonR;
        vec3 m = sky_moon(k, t, planetDir, planetSize, ringU, ringW, spin, moonR);
        float mb = dot(d, m);
        float md = mb * mb - (dot(m, m) - moonR * moonR);
        if (md <= 0.0) continue;
        float mt = mb - sqrt(md);
        if (mt >= moonT) continue;
        moonT = mt;
        vec3 mn = normalize(d * mt - m);
        float surface = cg_fbm3(noise, mn * 5.0 + float(k) * 7.0, 4);
        vec3 tone = k == 0 ? mix(vec3(0.45, 0.28, 0.2), vec3(0.85, 0.6, 0.45), surface)
                           : mix(vec3(0.55, 0.62, 0.72), vec3(1.0, 1.02, 1.08), surface);
        moonColor = tone * (max(dot(mn, VFX_KEY_DIR), 0.0) * 1.5 + max(dot(mn, VFX_FILL_DIR), 0.0) * 0.2 + 0.02);
    }
    float solidT = 1.0e9;
    if (disc > 0.0) {
        vec3 normal = normalize(d * planetT - planetDir);
        float lat = dot(normal, spin);
        float lon0 = atan(dot(normal, ringW), dot(normal, ringU));
        // Zonal winds: neighbouring bands blow opposite ways, carrying their eddies along with them, over the
        // planet's own turn.
        float lon = lon0 + t * 0.03;
        float wind = sin(lat * 22.0) * 0.12;
        vec3 flow = sky_flow(t, 6.0);
        float lonA = lon + wind * flow.x, lonB = lon + wind * flow.y + 2.3;
        float eddies = mix(cg_fbm3(noise, vec3(cos(lonB) * 3.0, sin(lonB) * 3.0, lat * 16.0), 4),
                           cg_fbm3(noise, vec3(cos(lonA) * 3.0, sin(lonA) * 3.0, lat * 16.0), 4), flow.z);
        float bands = cg_fbm3(noise, vec3(lat * 14.0 + (eddies - 0.5) * 1.2, 3.0, 1.0), 4);
        vec3 cloudColor = mix(vec3(0.75, 0.45, 0.25), vec3(1.15, 0.95, 0.7), bands);
        cloudColor = mix(cloudColor, vec3(0.5, 0.25, 0.45), smoothstep(0.62, 0.8, bands) * 0.6);
        // The storm: drifting with its band, spiralling as it turns.
        float spotLon = mod(lon0 - 0.6 + t * (0.03 + sin(-0.25 * 22.0) * 0.06) + 3.14159265, 6.2831853) - 3.14159265;
        vec2 spotAt = vec2(spotLon, (lat + 0.25) * 3.0);
        float swirl = 0.7 + 0.3 * cos(atan(spotAt.y, spotAt.x) * 2.0 - t * 1.5 + length(spotAt) * 12.0);
        float spot = exp(-dot(spotAt, spotAt) * 18.0) * swirl;
        cloudColor = mix(cloudColor, vec3(1.1, 0.35, 0.15), spot * 0.85);
        float sun = max(dot(normal, VFX_KEY_DIR), 0.0);
        // Where a moon passes between the planet and the key sun, its shadow falls on the clouds.
        vec3 onPlanet = d * planetT;
        for (int k = 0; k < 2; k++) {
            float moonR;
            vec3 m = sky_moon(k, t, planetDir, planetSize, ringU, ringW, spin, moonR);
            float toward = dot(m - onPlanet, VFX_KEY_DIR);
            float miss = length(m - onPlanet - VFX_KEY_DIR * toward) / moonR;
            if (toward > 0.0) sun *= mix(0.08, 1.0, smoothstep(0.8, 1.15, miss));
        }
        float fill = max(dot(normal, VFX_FILL_DIR), 0.0);
        float atmosphere = pow(1.0 - max(dot(normal, -d), 0.0), 3.0);
        vec3 planet = cloudColor * (sun * 1.6 + fill * 0.25 + 0.02) + vec3(0.6, 0.75, 1.2) * atmosphere * (sun + 0.15) * 0.8;
        color = planet;
        solidT = planetT;
    }
    if (moonT < solidT) {
        color = moonColor;
        solidT = moonT;
    }
    if (ringHere > 0.0 && ringT < solidT) color = mix(color, ringColor, clamp(ringBands, 0.0, 1.0) * (solidT < 1.0e8 ? 1.0 : 0.85));
    // Its atmosphere glowing just past its edge on the lit side.
    float pastEdge = acos(clamp(b, -1.0, 1.0)) - planetSize;
    color += vec3(0.5, 0.65, 1.1) * exp(-max(pastEdge, 0.0) / 0.01) * step(0.0, pastEdge) * 0.35;

    // A shooting star now and then: a bright head and a fading trail, a few seconds apart.
    float shotClock = t / 1.8;
    float shot = floor(shotClock);
    float progress = fract(shotClock) * 2.5;
    bool openSky = solidT > 1.0e8 && ringHere == 0.0;
    if (openSky && fx_hash31(vec3(shot, 4.0, 4.0)) > 0.35 && progress < 1.0) {
        vec3 start = normalize(fx_hash33(vec3(shot, 1.0, 1.0)) - vec3(0.5, 0.1, 0.5));
        vec3 heading = normalize(cross(start, normalize(fx_hash33(vec3(shot, 2.0, 2.0)) - 0.5)));
        vec3 plane = normalize(cross(start, heading));
        float off = abs(dot(d, plane));
        float arc = atan(dot(d, heading), dot(d, start));
        float head = progress * 0.5;
        float trail = smoothstep(head - 0.18, head, arc) * step(arc, head) * step(0.0, dot(d, start));
        color += vec3(1.2, 1.1, 1.4) * exp(-off / max(pixel * 1.2, 0.0015)) * trail * (1.0 - progress) * 3.0;
    }

    // The horizon: a violet glow, pinker toward the crimson giant.
    float horizon = exp(-abs(d.y) * 16.0);
    color += mix(vec3(0.35, 0.15, 0.6), vec3(0.9, 0.2, 0.45), exp(-toFill / 0.9)) * horizon * 0.5;
    color *= smoothstep(-0.25, 0.0, d.y) * 0.85 + 0.15;
    return fx_aces(0.8 * color);
}
