// =============================================================================
// CrystalShader -- loading/loading_fx.shader
// The loading screen's look, one quad material for every part (CgLogoLoadingView). Each quad says what it is in
// custom0: x the screen's seconds, y the screen's aspect, w the mode:
//   0 the backdrop: under the ocean looking up at the surface, with shafts of light, specks, and grain
//   1 a soft glow: a gaussian over the quad, in the quad's colour
//   2 an image (the lockup) with a light sweeping across it every few seconds, z its strength (0 for none)
//   3 the load bar: Minecraft's white outline and fill, made smooth, z how full
// =============================================================================
#domain global
#include "crystalgraphics:shaders/lib/noise.glsl"
Material { ColorSpace sRGB  Media On }
EngineBuffers { quad }

RenderState {
    Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
    Cull OFF
    DepthTest ALWAYS
    DepthWrite OFF
}

Properties {
    _MainTex ("Image", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
    vec4 color;
    vec4 params;
};





// Helpers before vertex(): a pass keeps what it declares ahead of its entry points
// A soft blob of `col` at c, radius r, mixed in by w
vec3 blob(vec3 dst, vec2 p, vec2 c, float r, vec3 col, float w) {
    vec2 d = p - c;
    return mix(dst, col, w * exp(-dot(d, d) / (r * r)));
}

// A cell grid `density` to the screen's height, rising `speed` cells a second (screen-down uv, so adding time
// rises): xy the point in its cell, centred; zw the cell
vec4 rising(vec2 uv, float t, float aspect, float density, float speed) {
    vec2 p = vec2(uv.x * aspect, uv.y) * density + vec2(0.0, t * speed);
    return vec4(fract(p) - 0.5, floor(p));
}

// The surface's slope at s (surface units): seven waves from long to short, spread by the golden angle, moving at
// deep water's speed (slowed), each as steep as the next
vec2 waveSlope(vec2 s, float t) {
    vec2 g = vec2(0.0);
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float a = 0.3 + fi * 2.39996, k = 6.2832 / (1.4 * pow(0.68, fi));
        vec2 d = vec2(cos(a), sin(a));
        g += d * 0.11 * cos(dot(d, s) * k + t * sqrt(9.8 * k) * 0.35 + fi * 1.7);
    }
    return g;
}

// The surface seen from below along d (y up): the sky through Snell's window, where light leaves the water, with
// the sun glinting off ripples; past the window's edge, total internal reflection mirrors the deep water
vec3 surfaceFromBelow(vec3 d, float t) {
    vec2 s = d.xz / d.y * 0.8;
    float dist = length(s);
    // Ripples calm with distance, or the horizon shimmers
    vec2 g = waveSlope(s + vec2(0.0, t * 0.04), t) / (1.0 + dist * 0.25);
    vec3 n = normalize(vec3(-g.x, 1.0, -g.y));
    float cosI = clamp(dot(d, n), 0.0, 1.0);
    float sinT2 = 1.7689 * (1.0 - cosI * cosI);     // 1.33^2: water to air
    // Stylised, not physical: a wide soft blend from window to mirror, and a mirror in the backdrop's light azure,
    // so the surface stays as airy as the rest of the screen
    float reflected = smoothstep(0.55, 1.05, sinT2);
    vec3 toSky = refract(d, -n, 1.33);
    vec3 sky = mix(vec3(0.42, 0.84, 1.00), vec3(0.78, 0.96, 1.00), clamp(toSky.y, 0.0, 1.0));
    sky += vec3(1.0, 0.98, 0.92) * pow(max(dot(toSky, normalize(vec3(0.15, 1.0, 0.35))), 0.0), 120.0) * 0.9;
    vec3 mirror = vec3(0.20, 0.52, 1.00) * (0.92 + 0.25 * g.x);
    vec3 col = mix(sky, mirror, reflected);
    // The window's edge, where the whole sky is squeezed into a bright ring
    col += vec3(0.70, 0.92, 1.00) * exp(-pow((sinT2 - 0.95) / 0.10, 2.0)) * 0.18;
    // Fine bright lines where the ripples cross one angle: the light lines seen looking up from a pool
    // Wider toward the horizon, keeping their light, or the far ones stack into horizontal stripes
    float lineWidth = 0.03 * (1.0 + dist * 0.5);
    col += vec3(0.88, 0.98, 1.00) * exp(-pow((sinT2 - 0.75) / lineWidth, 2.0)) * 0.32 / (1.0 + dist * 0.4);
    return mix(col, vec3(0.22, 0.60, 1.00), 1.0 - exp(-dist * 0.16));
}

// Under the ocean looking up: the surface from below in perspective down to a hazy horizon, light focused into
// wavy lines across it, shafts falling into water that deepens to indigo, and specks drifting up
vec3 backdrop(vec2 uv, float t, float aspect) {
    const float HORIZON = 0.40;
    vec2 centred = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
    vec3 col = mix(vec3(0.10, 0.50, 1.00), vec3(0.19, 0.12, 0.53), smoothstep(HORIZON - 0.05, 1.02, uv.y));
    col = blob(col, centred, vec2(-0.85, 0.55), 0.62, vec3(0.30, 0.19, 0.70), 0.65);
    col = blob(col, centred, vec2( 0.85, 0.55), 0.62, vec3(0.52, 0.19, 0.64), 0.65);

    // The surface overhead: rows above the horizon look up at it, steeper toward the top
    vec3 view = normalize(vec3(centred.x, max(HORIZON - uv.y, 0.002) * 4.0, 1.0));
    col = mix(col, surfaceFromBelow(view, t), smoothstep(HORIZON + 0.06, HORIZON - 0.04, uv.y));
    col = mix(col, vec3(0.55, 0.88, 1.00), exp(-pow((uv.y - HORIZON) / 0.035, 2.0)) * 0.28);

    // A calm column behind the logo, bar and status: fewer shafts and specks, the water a little deeper
    float column = exp(-pow(centred.x / 0.42, 2.0)) * smoothstep(HORIZON - 0.05, HORIZON + 0.10, uv.y);
    col *= 1.0 - 0.10 * column;

    // The sun above the surface, off to the left: a soft glow, which the shafts fan out from
    vec2 sun = vec2(-0.70, -0.72);
    vec2 toSun = centred - sun;
    col += vec3(0.62, 0.90, 1.00) * exp(-dot(toSun, toSun) / 0.10) * 0.20;

    // Shafts falling from the surface, shifting slowly
    float angle = atan(toSun.x, toSun.y);
    float shafts = value_noise(vec2(angle * 14.0, t * 0.25)) * value_noise(vec2(angle * 31.0 + 4.0, t * 0.4));
    shafts = smoothstep(0.12, 0.6, shafts) * smoothstep(HORIZON - 0.1, HORIZON + 0.1, uv.y) * exp(-(uv.y - HORIZON) * 2.0);
    col += vec3(0.40, 0.80, 1.00) * shafts * 0.42 * (1.0 - 0.65 * column);

    // Specks drifting up: small and faint far off, a few large and soft close by, faster. They fade out under the
    // surface rather than rising across it
    float underwater = smoothstep(HORIZON - 0.02, HORIZON + 0.10, uv.y);
    for (int layer = 0; layer < 2; layer++) {
        bool far = layer == 0;
        vec4 g = rising(uv, t, aspect, far ? 18.0 : 6.0, far ? 0.10 : 0.30);
        vec2 h = hash22(g.zw + 61.0 + float(layer) * 13.0);
        vec2 d = g.xy - (h - 0.5) * 0.6 - vec2(sin(t * 0.8 + h.y * 6.2832) * 0.1, 0.0);
        float size = far ? 0.0020 : 0.012;
        float speck = exp(-dot(d, d) / size) * step(far ? 0.80 : 0.86, hash12(g.zw + 64.7 + float(layer)))
                * (far ? 0.30 + 0.25 * h.x : 0.14 + 0.08 * h.x) * (1.0 - 0.7 * column) * underwater;
        col = mix(col, vec3(0.88, 0.96, 1.00), speck);
    }
    return col;
}

float grain(vec2 pixel, float t, float amount) {
    return (hash12(pixel + fract(t * 7.13) * 113.0) - 0.5) * amount;
}

// Straight-alpha `src` over premultiplied `dst`
vec4 over(vec4 dst, vec3 rgb, float a) {
    return vec4(rgb * a, a) + dst * (1.0 - a);
}

// Signed distance from q to a box of half-size e with corners rounded by r
float roundBox(vec2 q, vec2 e, float r) {
    vec2 a = abs(q) - (e - r);
    return length(max(a, 0.0)) + min(max(a.x, a.y), 0.0) - r;
}

// Minecraft's load bar made smooth: a white outline, a gap, then a solid white fill to `fill` (0 to 1), over a
// dark track. b pixels tall, centred in a quad 4b tall and 3b wider than it.
// `size` is the quad in pixels, 1 / fwidth(uv), taken by the fragment: a helper here reaches the vertex stage too
vec4 bar(vec2 uv, vec2 size, float fill, float alpha) {
    vec2 p = uv * size, c = size * 0.5;
    float b = size.y * 0.25, r = b * 0.22, line = max(1.5, b * 0.12);
    vec2 extent = vec2(size.x * 0.5 - 1.5 * b, b * 0.5);
    float d = roundBox(p - c, extent, r);
    float x0 = 1.5 * b + 2.0 * line, x1 = size.x - 1.5 * b - 2.0 * line;
    float edge = x0 + fill * (x1 - x0);
    float filled = 1.0 - smoothstep(edge - 0.5, edge + 0.5, p.x);

    vec4 pm = vec4(0.0);
    pm = over(pm, vec3(0.04, 0.04, 0.16), (1.0 - smoothstep(-0.5, 0.5, d)) * 0.30);
    pm = over(pm, vec3(1.0), (smoothstep(-line - 0.5, -line + 0.5, d) - smoothstep(-0.5, 0.5, d)) * 0.95);
    pm = over(pm, vec3(1.0), (1.0 - smoothstep(-0.5, 0.5, d + 2.0 * line)) * filled * 0.97);
    return vec4(pm.rgb / max(pm.a, 1.0e-4), pm.a * alpha);
}

// A diagonal band crossing an image in 1.2 s, every 4.5 s
float sweep(vec2 uv, float t) {
    float phase = mod(t, 4.5) / 1.2;
    float across = (uv.x + (1.0 - uv.y) * 0.6) / 1.6;
    return phase < 1.3 ? exp(-pow((across - (phase * 1.6 - 0.3)) / 0.07, 2.0)) : 0.0;
}



void vertex(out v2f o) {
    gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
    o.uv     = CG_QUAD_UV;
    o.color  = CG_QUAD_COLOR;
    o.params = CG_QUAD_CUSTOM0;
}

void fragment(in v2f i, out vec4 fragColor) {
    int mode = int(i.params.w + 0.5);
    float t = i.params.x, aspect = i.params.y;
    if (mode == 0) {
        fragColor = vec4(backdrop(i.uv, t, aspect) + grain(gl_FragCoord.xy, t, 0.010), i.color.a);
    } else if (mode == 1) {
        // Round, and at zero before the quad's edge, or the edge shows as a square
        float r = length(i.uv * 2.0 - 1.0);
        fragColor = vec4(i.color.rgb, i.color.a * exp(-r * r * 3.0) * (1.0 - smoothstep(0.55, 1.0, r)));
    } else if (mode == 2) {
        vec4 image = texture(_MainTex, i.uv) * i.color;
        image.rgb += vec3(0.55 * sweep(i.uv, t) * i.params.z);
        fragColor = image;
    } else {
        fragColor = bar(i.uv, 1.0 / max(fwidth(i.uv), vec2(1.0e-6)), i.params.z, i.color.a);
    }
}
