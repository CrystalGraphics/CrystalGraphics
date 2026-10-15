// Marching a gas's brick (CgVfxGas, CgVfxGasAtlas): where a point of its cube reads the atlas, the span of a ray
// through the cube, how smoke scatters light, and the colour of what burns in it. Every value is an argument.
//
//     vec2 t = fx_gas_box(eye, ray, lo, hi);                                    // enters at t.x, leaves at t.y
//     vec4 s = textureLod(_GasShade, fx_gas_uvw((p - lo) / extent, brick, cells, bricks), 0.0);
//     float lit = fx_gas_phase(dot(ray, sun), 0.55) * s.z;                      // s.z: its transmittance to the sun
#pragma once

// The atlas coordinate of local (0 to 1 across the brick) in brick b of cells a side, bricks along x: held half a cell
// inside the brick, so a filtered read never takes its neighbour's.
vec3 fx_gas_uvw(vec3 local, float b, float cells, float bricks) {
    vec3 c = clamp(local * cells, vec3(0.5), vec3(cells - 0.5));
    return vec3((b * cells + c.x) / (cells * bricks), c.y / cells, c.z / cells);
}

// Where a ray from o along unit d enters and leaves the box lo to hi; y below x for a miss.
vec2 fx_gas_box(vec3 o, vec3 d, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / d;
    vec3 a = (lo - o) * inv, b = (hi - o) * inv;
    vec3 near = min(a, b), far = max(a, b);
    return vec2(max(max(near.x, near.y), near.z), min(min(far.x, far.y), far.z));
}

// Henyey-Greenstein's phase for the cosine between the ray and the light: g above 0 forward, below back.
float fx_hg(float cosine, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.5663706 * pow(max(1.0 + g2 - 2.0 * g * cosine, 1.0e-4), 1.5));
}

// Smoke's phase, a strong forward lobe and a weak back one (Frostbite's and Horizon's two lobes), times 4 pi so light
// scattered evenly is 1: the silver edge toward the sun, and a face lit from behind.
float fx_gas_phase(float cosine, float g) {
    return mix(fx_hg(cosine, g), fx_hg(cosine, -0.3), 0.25) * 12.5663706;
}

// A blackbody's colour at kelvin, linear, its brightest channel 1: Planck's law at 610, 550 and 465 nm.
vec3 fx_blackbody(float kelvin) {
    vec3 l = vec3(0.61, 0.55, 0.465);
    vec3 b = 1.0 / (l * l * l * l * l * (exp(14388.0 / (l * max(kelvin, 500.0))) - 1.0));
    return b / max(b.r, max(b.g, b.b));
}

// What burns at a gas's temperature (over the air's, the solver's units): nothing below 1, a dull red flame to a
// yellow-white one by 4, as bright as Stefan-Boltzmann's fourth power says.
vec3 fx_gas_fire(float temperature) {
    float kelvin = 700.0 + 450.0 * temperature;
    float bright = kelvin / 1800.0;
    return fx_blackbody(kelvin) * bright * bright * bright * bright * smoothstep(1.0, 2.0, temperature);
}
