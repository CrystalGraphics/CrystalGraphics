// Impact frames (CgImpactFrame) for the post composite's copy form: a hash, focus lines, a flash star with its cross,
// the looks drawn from the picture alone, and the drawn frame over a subject texture with its hatching. Nothing here
// names cg_* or a material property: a shader passes its pixel, size, focus, uniforms and the texture its subject is
// read from.
//
//     vec2 p = (uv - _Focus.xy) * vec2(CG_RESOLUTION.x / CG_RESOLUTION.y, 1.0);
//     vec3 look = _Impact.y > 2.5
//             ? impact_drawn(_Subject, vec2(0.3, 0.0), uv, CG_RESOLUTION, _Focus.xy, _Impact, _ImpactDraw, _ImpactMore,
//                            _ImpactLight.rgb, _ImpactDark.rgb)
//             : impact_picture(encoded, _Impact.y, p, uint(_Impact.z), 1.0 / CG_RESOLUTION.y);
//
// Distances are in screen heights from the focus; px is a pixel in them.
#pragma once
#include "crystalgraphics:shaders/lib/post/composite.glsl"

// PCG's hash (Jarzynski and Olano, JCGT 2020), and two words to 0..1: the lines and strokes, drawn anew per seed.
uint impact_pcg(uint x) {
    uint s = x * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

float impact_rand(uint a, uint b) {
    return float(impact_pcg(a ^ impact_pcg(b))) * (1.0 / 4294967296.0);
}

// How much of a pixel a needle covers: its half-width and the pixel's distance from its axis.
float impact_needle(float halfWidth, float dist, float px) {
    return clamp((halfWidth - dist) / px + 0.5, 0.0, 1.0);
}

// Focus lines (manga's 集中線; Clip Studio's saturated-line ruler), 0..1 ink: needles sharp at their inner end and
// thickening outward, each with its own angle, inner end, length and width, gathered in bundles with gaps; amount 0..1
// thins them.
float impact_focus_lines(vec2 p, uint seed, float amount, float px) {
    const float N = 300.0, BUNDLE = 7.0;
    float r = length(p), a = (atan(p.y, p.x) / 6.2831853 + 0.5) * N, ink = 0.0;
    for (int k = -1; k <= 1; k++) {
        float c = mod(floor(a) + float(k), N);
        uint id = uint(c);
        float bundle = impact_rand(uint(floor(c / BUNDLE)), seed ^ 0x9e3779b9u);
        if (impact_rand(id, seed ^ 0x85ebca6bu) > amount * (0.25 + bundle)) continue;
        float d = a - (c + 0.5 + (impact_rand(id, seed ^ 0xc2b2ae35u) - 0.5) * 0.9);
        d -= N * floor(d / N + 0.5);
        float inner = 0.07 + 0.38 * pow(impact_rand(id, seed ^ 0x27d4eb2fu), 2.0);
        float outer = inner + 0.35 + 1.2 * impact_rand(id, seed ^ 0x165667b1u);
        float spread = 0.0025 + 0.012 * impact_rand(id, seed ^ 0xd3a2646cu);
        ink = max(ink, impact_needle(spread * min(r - inner, 0.35 * (outer - r)), abs(d) * (6.2831853 / N) * r, px));
    }
    return ink;
}

// A flash star at the focus, ink 0..1: x its core, size across; y the short spikes round it.
vec2 impact_star(vec2 p, uint seed, float size, float px) {
    const float N = 28.0;
    float r = length(p), a = (atan(p.y, p.x) / 6.2831853 + 0.5) * N, spikes = 0.0;
    for (int k = -1; k <= 1; k++) {
        float c = mod(floor(a) + float(k), N);
        uint id = uint(c) + 977u;
        float d = a - (c + 0.5 + (impact_rand(id, seed) - 0.5) * 0.6);
        d -= N * floor(d / N + 0.5);
        float len = size * (1.4 + 2.6 * pow(impact_rand(id, seed ^ 0x9e3779b9u), 2.0));
        float halfWidth = size * 0.22 * clamp((len - r) / (len - 0.6 * size), 0.0, 1.0);
        spikes = max(spikes, impact_needle(halfWidth, abs(d) * (6.2831853 / N) * r, px));
    }
    return vec2(impact_needle(size, r, px), spikes);
}

// A thin cross through the focus, across the screen, thinning away from it.
float impact_cross(vec2 p, float size, float px) {
    float across = max(px, size * 0.12 * exp(-2.0 * abs(p.x))), down = max(px, size * 0.12 * exp(-2.5 * abs(p.y)));
    return max(impact_needle(across, abs(p.y), px), impact_needle(down, abs(p.x), px));
}

// The looks drawn from the picture alone (CgImpactFrame's kinds 0 to 2) of an encoded colour e: its negative, stark
// black and white, or dark focus lines over it.
vec3 impact_picture(vec3 e, float look, vec2 p, uint seed, float px) {
    if (look < 0.5) return 1.0 - clamp(e, 0.0, 1.0);
    if (look < 1.5) return vec3(step(0.5, dot(clamp(e, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722))));
    return e * (1.0 - impact_focus_lines(p, seed, 1.0, px));
}

// A drawn frame reads its subject from one texture: what the effect glows with, the HDR scene or the picture. mode.x
// is the light the subject starts at, mode.y 1 where the texture is sRGB.
//
// Fragment-only (fwidth), guarded as lib/sdf.glsl guards its coverage: a material's include reaches both stages, and
// a guard written in a pass's own declarations is hoisted away from what it guards.
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// The subject's field at uv: past 0 inside, its threshold's share past it.
float impact_field(sampler2D tex, vec2 mode, vec2 uv) {
    vec3 c = textureLod(tex, uv, 0.0).rgb;
    if (mode.y > 0.5) c = post_decode_srgb(c);
    return max(c.r, max(c.g, c.b)) / mode.x - 1.0;
}

// The field blurred over a tent of five taps, o apart: the shape is cut from this, so it reads as a drawn mass.
float impact_soft(sampler2D tex, vec2 mode, vec2 uv, vec2 o) {
    return 0.4 * impact_field(tex, mode, uv) + 0.15 * (impact_field(tex, mode, uv + o)
            + impact_field(tex, mode, uv + vec2(-o.x, o.y)) + impact_field(tex, mode, uv + vec2(o.x, -o.y))
            + impact_field(tex, mode, uv - o));
}

// Strokes off the subject's edge, outward along the ray from the focus: thick at the edge and a needle at their
// length, at most len long. origin is the focus where the subject is read.
float impact_hatch(sampler2D tex, vec2 mode, vec2 p, vec2 origin, vec2 aspect, uint seed, float len, float px) {
    float r = length(p);
    if (r < 1e-4) return 0.0;
    vec2 dir = p / r;
    // The subject's edge back along the ray, found in eight steps and placed between the last two by its field.
    float edge = -1.0, was = impact_field(tex, mode, origin + p / aspect);
    for (int i = 1; i <= 8; i++) {
        float s = float(i) * (len / 8.0);
        if (s > r) break;
        float f = impact_field(tex, mode, origin + (p - dir * s) / aspect);
        if (f > 0.0) {
            edge = s - (len / 8.0) * f / max(f - was, 1e-4);
            break;
        }
        was = f;
    }
    if (edge < 0.0) return 0.0;
    const float N = 900.0;
    float a = (atan(p.y, p.x) / 6.2831853 + 0.5) * N, ink = 0.0;
    for (int k = -1; k <= 1; k++) {
        float c = mod(floor(a) + float(k), N);
        uint id = uint(c) + 4099u;
        if (impact_rand(id, seed ^ 0x85ebca6bu) > 0.8) continue;
        float d = a - (c + 0.5 + (impact_rand(id, seed) - 0.5) * 0.9);
        d -= N * floor(d / N + 0.5);
        float reach = len * (0.3 + 0.7 * impact_rand(id, seed ^ 0x27d4eb2fu));
        float halfWidth = (0.6 + 1.8 * impact_rand(id, seed ^ 0x165667b1u)) * px * (1.0 - edge / reach);
        ink = max(ink, impact_needle(halfWidth, abs(d) * (6.2831853 / N) * r, px));
    }
    return ink;
}

// A drawn frame at uv, encoded: paper, the subject in ink or left paper, focus lines stopping at the subject, hatching
// off its edge and the star, every drawing shifted by the seed's jitter. impact, draw and more are the composite's
// _Impact, _ImpactDraw and _ImpactMore; light and dark its two tones, linear.
vec3 impact_drawn(sampler2D tex, vec2 mode, vec2 uv, vec2 res, vec2 focus, vec4 impact, vec4 draw, vec4 more,
                  vec3 light, vec3 dark) {
    vec2 aspect = vec2(res.x / res.y, 1.0);
    float px = 1.0 / res.y, o = 6.0 * px;
    uint seed = uint(impact.z);
    vec2 shake = (vec2(impact_rand(seed, 11u), impact_rand(seed, 23u)) - 0.5) * (2.0 * more.y) / res;
    vec2 p = (uv - focus - shake) * aspect;
    float field = impact_soft(tex, mode, uv - shake, vec2(o / aspect.x, o));
    float cover = clamp(field / max(fwidth(field), 1e-4) + 0.5, 0.0, 1.0);
    float paper = impact.w, ink = 1.0 - paper, v = paper;
    if (draw.x > 0.5) v = mix(v, ink, cover);
    if (draw.y > 0.0) {
        // Clear of the subject grown by a little, so the lines stop short of it.
        float grown = clamp((impact_soft(tex, mode, uv - shake, 2.5 * vec2(o / aspect.x, o)) + 0.55) * 4.0, 0.0, 1.0);
        v = mix(v, ink, impact_focus_lines(p, seed, draw.y, px) * (1.0 - grown));
    }
    if (draw.z > 0.0) v = mix(v, ink, impact_hatch(tex, mode, p, focus, aspect, seed, draw.z, px) * (1.0 - cover));
    if (draw.w > 0.0) {
        vec2 star = impact_star(p, seed, draw.w, px);
        if (more.x > 0.5) star.y = max(star.y, impact_cross(p, draw.w, px));
        v = mix(v, ink, star.y);   // spikes and cross in ink; the core is always the light tone
        v = mix(v, 1.0, star.x);
    }
    return post_encode_srgb(mix(dark, light, v));
}
#endif
