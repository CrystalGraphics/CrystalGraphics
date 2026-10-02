// Lightning on CgVfxRibbons: the jagged shape of a channel, its strobing return strokes, and the light across it. The
// shader supplies the channel's base path and the plane to jag it in; nothing here names cg_* or CG_*.
//
//     vec2 off = fx_bolt_offset(t, boltSeed) * jag * chordLength;          // pinned to 0 at t = 0 and t = 1
//     vec3 p = base(t) + up * off.x + side * off.y;
//     float bright = fx_bolt_strobe(moment, boltSeed);                       // moment 0..1 over the bolt's life
//     // fragment:
//     vec3 l = fx_bolt_profile(across, fwidth(across), 0.06);               // core, halo, glow
//
// A fork leaves its channel where it branches: evaluate the fork's own path, then add (channelPoint - forkBase(0)) *
// (1 - t), so it starts exactly on the channel and ends where it aimed.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"

// Midpoint displacement across a channel at t (0..1), in units of the chord: sharp kinks at every scale, the coarse ones
// largest, 0 at both ends. Fixed for a given seed, so a strike keeps its channel for its whole life.
vec2 fx_bolt_offset(float t, float seed) {
    vec2 sum = vec2(0.0);
    float amplitude = 0.28;
    for (int o = 0; o < 5; o++) {
        float n = exp2(float(o + 1));
        float x = t * n, i = floor(min(x, n - 1.0)), f = x - i;
        vec2 a = fx_hash41(seed + i * 7.31 + float(o) * 113.7).xy * 2.0 - 1.0;
        vec2 b = fx_hash41(seed + (i + 1.0) * 7.31 + float(o) * 113.7).xy * 2.0 - 1.0;
        a *= step(0.5, i);
        b *= step(i + 1.5, n);
        sum += mix(a, b, f) * amplitude;
        amplitude *= 0.58;
    }
    return sum;
}

// A strike's brightness over its life (moment 0..1): the first stroke, then up to two dimmer return strokes down the same
// channel, gone by the end.
float fx_bolt_strobe(float moment, float seed) {
    vec4 h = fx_hash41(seed * 1.37 + 5.0);
    float t1 = 0.22 + 0.18 * h.x, t2 = 0.5 + 0.2 * h.y;
    float flash = exp(-moment * 9.0);
    flash += step(t1, moment) * exp(-(moment - t1) * 14.0) * (0.45 + 0.5 * h.z);
    flash += step(t2, moment) * step(0.35, h.w) * exp(-(moment - t2) * 14.0) * (0.3 + 0.4 * h.w);
    return flash * (1.0 - smoothstep(0.8, 1.0, moment));
}

// The light across a bolt's ribbon, across being -1..1 over its half-width and aa its screen-space rate (the caller's
// fwidth). x: a white-hot core, `core` of the half-width and never under about a pixel, dimmed when held wider so a
// distant bolt does not swell; y: a tight halo round it; z: the broad glow.
vec3 fx_bolt_profile(float across, float aa, float core) {
    float x = abs(across);
    float width = max(core, aa);
    float line = (1.0 - smoothstep(width - 0.75 * aa, width + 0.75 * aa, x)) * sqrt(core / width);
    float halo = exp(-pow(x / (core * 2.5), 2.0));
    float glow = exp(-x * x * 5.0);
    return vec3(line, halo, glow);
}
