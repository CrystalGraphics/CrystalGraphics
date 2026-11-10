// A text style's paint for CgTextDemo's shader stations: one Text-domain material, its effect picked by _Effect, so
// each station is a material definition of it with its own value and every station shares one program. The glyph's
// coverage is cg_text_layer's (env/buffer/text.glsl); this paints under it, across the whole run (cg_text_run_uv).
//
//   0 plasma     1 hologram    2 rainbow (HSV, cycling)    3 fire    4 electric    5 glitch    6 galaxy

#type none
#pragma cg_use quad
#pragma cg_use clip
#pragma cg_use text

#pragma cg_feature MSDF_MODE

#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"

Tags {
    "RenderType" = "Transparent"
    "Domain" = "Text"
}

Queue = "Overlay"

Properties {
    _MainTex ("Atlas Texture", sampler2DArray) = "white"
    _Effect  ("Which effect: 0 plasma, 1 hologram, 2 rainbow, 3 fire, 4 electric, 5 glitch, 6 galaxy", float) = 0
}

struct v2f {
    vec2 uv;
    vec4 color;
    float atlasLayer;
    vec2 param;
    vec4 gammaSmall;
    vec4 gammaLarge;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Text"
    }

    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        Cull OFF
        DepthTest LEQUAL
        DepthWrite OFF
    }

    // run: 0 to 1 across the whole text, y down. Each answers straight colour and alpha.

    vec4 plasma(vec2 run, float t) {
        float v = sin(run.x * 11.0 + t * 2.1) + sin((run.y * 5.0 + t) * 1.7) + sin(run.x * 6.0 + run.y * 4.0 - t * 1.3)
                + sin(length(run - vec2(0.5 + 0.4 * sin(t * 0.7), 0.5)) * 14.0 - t * 2.5);
        return vec4(0.5 + 0.5 * cos(6.28318 * (v * 0.18 + vec3(0.0, 0.33, 0.67)) + t * 0.5), 1.0);
    }

    vec4 hologram(vec2 run, float t) {
        float lines = 0.55 + 0.45 * sin((run.y * 22.0 - t * 3.0) * 6.28318);
        float sweep = exp(-pow((run.x - fract(t * 0.35) * 1.6 + 0.3) * 4.0, 2.0));
        vec3 iridescent = 0.5 + 0.5 * cos(6.28318 * (run.x * 1.5 + t * 0.2 + vec3(0.0, 0.33, 0.67)));
        float flicker = 0.82 + 0.18 * step(0.12, hash12(vec2(floor(t * 14.0), 3.7)));
        return vec4(mix(vec3(0.25, 0.9, 1.0), iridescent, 0.35) * lines + vec3(sweep * 0.8), flicker);
    }

    // A full turn of the hue across the word, flowing left to right, a band of shine riding it.
    vec4 rainbow(vec2 run, float t) {
        float hue = fract(run.x * 0.9 + run.y * 0.12 - t * 0.3);
        vec3 c = hsv_to_rgb(vec3(hue, 0.85, 1.0));
        float shine = smoothstep(0.08, 0.0, abs(fract(run.x * 0.5 - t * 0.25) - 0.5) - 0.42);
        return vec4(mix(c, vec3(1.0), shine * 0.6 * (1.0 - run.y)), 1.0);
    }

    // Flames rising through the letters: hottest at their feet, licked upward by scrolling noise.
    vec4 fire(vec2 run, float t) {
        vec2 p = vec2(run.x * 7.0, run.y * 3.0 + t * 2.2);
        float n = fbm4(p) * 0.7 + fbm4(p * 2.3 + vec2(0.0, t * 1.5)) * 0.3;
        float heat = clamp(run.y * 1.1 + (n - 0.5) * 1.2, 0.0, 1.0);
        vec3 c = mix(vec3(0.35, 0.02, 0.0), vec3(1.0, 0.25, 0.0), smoothstep(0.1, 0.45, heat));
        c = mix(c, vec3(1.0, 0.75, 0.15), smoothstep(0.45, 0.75, heat));
        c = mix(c, vec3(1.0, 0.98, 0.85), smoothstep(0.78, 1.0, heat));
        return vec4(c, 1.0);
    }

    // Three bolts wandering across the run over a dark charge, each re-struck at its own rate.
    vec4 electric(vec2 run, float t) {
        vec3 c = vec3(0.02, 0.06, 0.25) + vec3(0.0, 0.1, 0.3) * fbm4(run * vec2(8.0, 3.0) + t);
        for (int k = 0; k < 3; k++) {
            float strike = floor(t * (5.0 + float(k) * 2.0));
            float path = 0.5 + 0.7 * (fbm4(vec2(run.x * 5.0 + strike * 1.7, float(k) * 9.1 + strike)) - 0.5);
            float bolt = exp(-abs(run.y - path) * 55.0);
            c += vec3(0.55, 0.85, 1.0) * bolt * (0.6 + 0.4 * hash12(vec2(strike, float(k))));
        }
        return vec4(min(c, vec3(1.0)), 1.0);
    }

    // Bands torn sideways a few times a second, each splitting into red and blue; some drop out.
    vec4 glitch(vec2 run, float t) {
        float tick = floor(t * 9.0);
        float band = floor(run.y * 10.0);
        float r = hash12(vec2(band, tick));
        float tear = r > 0.7 ? (hash12(vec2(band + 5.0, tick)) - 0.5) * 0.3 : 0.0;
        float x = run.x + tear;
        vec3 c = vec3(0.9 + 0.1 * sin(x * 40.0), 0.25 + 0.6 * step(0.5, fract(x * 6.0 + tick * 0.13)), 1.0);
        c = mix(vec3(0.1, 1.0, 0.85), c, step(0.35, fract(x * 3.0 - tick * 0.21)));
        float scan = 0.8 + 0.2 * sin(run.y * 160.0);
        float dropout = r > 0.93 ? 0.15 : 1.0;
        return vec4(c * scan, dropout);
    }

    // Nebula clouds drifting behind twinkling stars.
    vec4 galaxy(vec2 run, float t) {
        vec2 p = run * vec2(6.0, 2.5);
        float n = fbm6(p + vec2(t * 0.15, -t * 0.05));
        float m = fbm4(p * 1.7 - vec2(t * 0.1, 0.0));
        vec3 c = mix(vec3(0.05, 0.02, 0.18), vec3(0.45, 0.1, 0.6), smoothstep(0.3, 0.8, n));
        c = mix(c, vec3(0.1, 0.55, 0.9), smoothstep(0.55, 0.9, m) * 0.7);
        vec2 cell = floor(run * vec2(90.0, 30.0));
        float star = step(0.93, hash12(cell));
        float twinkle = 0.5 + 0.5 * sin(t * 4.0 + hash12(cell + 7.0) * 30.0);
        return vec4(c + vec3(star * twinkle), 1.0);
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv         = CG_QUAD_UV;
        o.color      = CG_QUAD_COLOR;
        o.atlasLayer = CG_QUAD_ATLAS_LAYER;
        o.param      = CG_VERTEX_CORNER;
        o.gammaSmall = text_gamma_terms(CG_QUAD_COLOR.rgb, u_TextGammaSmall);
        o.gammaLarge = text_gamma_terms(CG_QUAD_COLOR.rgb, u_TextGammaLarge);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 run = cg_text_run_uv(i.param);
        float t = CG_TIME;
        int effect = int(_Effect + 0.5);
        vec4 paint = effect == 1 ? hologram(run, t) : effect == 2 ? rainbow(run, t) : effect == 3 ? fire(run, t)
                : effect == 4 ? electric(run, t) : effect == 5 ? glitch(run, t) : effect == 6 ? galaxy(run, t)
                : plasma(run, t);
        fragColor = cg_text_layer(_MainTex, i.uv, i.atlasLayer, i.param, paint * i.color, i.gammaSmall, i.gammaLarge);
    }
}
