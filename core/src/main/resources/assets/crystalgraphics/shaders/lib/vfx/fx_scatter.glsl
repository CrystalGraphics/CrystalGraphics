// Light in participating media, for any marched volume (a fluid, a grid): the span of a ray through a box, and how
// smoke scatters the light reaching it. Every value is an argument.
//
//     vec2 t = fx_scatter_box(eye, ray, lo, hi);                       // enter, leave; y below x for a miss
//     float lit = fx_scatter_phase(dot(ray, sun), 0.6) * transmittance * fx_scatter_powder(sigma, dot(ray, sun));
#pragma once

// Where a ray from o along unit d enters and leaves the box lo to hi; y below x for a miss.
vec2 fx_scatter_box(vec3 o, vec3 d, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / d;
    vec3 a = (lo - o) * inv, b = (hi - o) * inv;
    vec3 near = min(a, b), far = max(a, b);
    return vec2(max(max(near.x, near.y), near.z), min(min(far.x, far.y), far.z));
}

// Henyey-Greenstein's phase for the cosine between the ray and the light: g above 0 forward, below back.
float fx_scatter_hg(float cosine, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.5663706 * pow(max(1.0 + g2 - 2.0 * g * cosine, 1.0e-4), 1.5));
}

// Smoke's phase, a strong forward lobe and a weak back one (Frostbite's and Horizon's two lobes), times 4 pi so light
// scattered evenly is 1: the silver edge toward the sun, and a face lit from behind.
float fx_scatter_phase(float cosine, float g) {
    return mix(fx_scatter_hg(cosine, g), fx_scatter_hg(cosine, -0.3), 0.25) * 12.5663706;
}

// Horizon Zero Dawn's powder (Schneider 2015): a thin edge scatters less light toward the eye than a deep one, except
// looking toward the sun. sigma is the extinction a block where the step is.
float fx_scatter_powder(float sigma, float cosine) {
    return mix(1.0 - exp(-2.0 * sigma), 1.0, 0.5 + 0.5 * cosine);
}
