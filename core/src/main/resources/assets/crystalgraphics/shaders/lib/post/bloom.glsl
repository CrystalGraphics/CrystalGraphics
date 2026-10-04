// The bloom chain's filters, Call of Duty: Advanced Warfare's (Jimenez, SIGGRAPH 2014), as Bevy, Filament and
// LearnOpenGL's physically based bloom run them: a 13-tap downsample, with Karis's firefly average on the first step
// only, and a 3x3 tent upsample added level by level. Each takes the source and its texel size; nothing here names cg_*.
//
//     vec3 down = post_downsample13(_Source, uv, 1.0 / vec2(textureSize(_Source, 0)), karis);
//     vec3 up = post_tent(_Source, uv, 1.0 / vec2(textureSize(_Source, 0)));
#pragma once

float post_luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Thirteen bilinear fetches over a 6x6 texel footprint, as five overlapping 4x4 boxes: the centre weighted 0.5, each
// corner 0.125. A box downsample flickers as small bright things move; this does not. With karis, each box is weighted
// by 1 / (1 + luma) before they are summed, so one very bright texel cannot dominate its block.
vec3 post_downsample13(sampler2D s, vec2 uv, vec2 texel, bool karis) {
    vec3 a = texture(s, uv + texel * vec2(-2.0, 2.0)).rgb;
    vec3 b = texture(s, uv + texel * vec2(0.0, 2.0)).rgb;
    vec3 c = texture(s, uv + texel * vec2(2.0, 2.0)).rgb;
    vec3 d = texture(s, uv + texel * vec2(-1.0, 1.0)).rgb;
    vec3 e = texture(s, uv + texel * vec2(1.0, 1.0)).rgb;
    vec3 f = texture(s, uv + texel * vec2(-2.0, 0.0)).rgb;
    vec3 g = texture(s, uv).rgb;
    vec3 h = texture(s, uv + texel * vec2(2.0, 0.0)).rgb;
    vec3 i = texture(s, uv + texel * vec2(-1.0, -1.0)).rgb;
    vec3 j = texture(s, uv + texel * vec2(1.0, -1.0)).rgb;
    vec3 k = texture(s, uv + texel * vec2(-2.0, -2.0)).rgb;
    vec3 l = texture(s, uv + texel * vec2(0.0, -2.0)).rgb;
    vec3 m = texture(s, uv + texel * vec2(2.0, -2.0)).rgb;
    vec3 centre = (d + e + i + j) * 0.25;
    vec3 tl = (a + b + f + g) * 0.25;
    vec3 tr = (b + c + g + h) * 0.25;
    vec3 bl = (f + g + k + l) * 0.25;
    vec3 br = (g + h + l + m) * 0.25;
    if (!karis) return centre * 0.5 + (tl + tr + bl + br) * 0.125;
    float wc = 0.5 / (1.0 + post_luma(centre));
    float w1 = 0.125 / (1.0 + post_luma(tl)), w2 = 0.125 / (1.0 + post_luma(tr));
    float w3 = 0.125 / (1.0 + post_luma(bl)), w4 = 0.125 / (1.0 + post_luma(br));
    return (centre * wc + tl * w1 + tr * w2 + bl * w3 + br * w4) / (wc + w1 + w2 + w3 + w4);
}

// A 3x3 tent (1 2 1, 2 4 2, 1 2 1) over the level below, nine bilinear fetches one texel apart.
vec3 post_tent(sampler2D s, vec2 uv, vec2 texel) {
    vec3 r = texture(s, uv).rgb * 4.0;
    r += (texture(s, uv + vec2(texel.x, 0.0)).rgb + texture(s, uv - vec2(texel.x, 0.0)).rgb
        + texture(s, uv + vec2(0.0, texel.y)).rgb + texture(s, uv - vec2(0.0, texel.y)).rgb) * 2.0;
    r += texture(s, uv + texel).rgb + texture(s, uv - texel).rgb
       + texture(s, uv + vec2(texel.x, -texel.y)).rgb + texture(s, uv + vec2(-texel.x, texel.y)).rgb;
    return r * (1.0 / 16.0);
}
