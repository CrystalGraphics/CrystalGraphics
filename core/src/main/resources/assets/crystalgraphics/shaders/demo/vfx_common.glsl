// What every material of the VFX showcase shares (CgVfxShowcase): the camera, 3D noise, the studio environment
// reflections read, GGX lighting and the filmic curve every material ends on. Pure functions: nothing here reads a
// derivative, so it compiles into both stages.
#pragma once

#include "crystalgraphics:shaders/lib/math.glsl"

// The camera's position in the world the draws are in: the view's inverse translation. At the origin in a
// camera-relative world (a Minecraft host), wherever the harness put it otherwise. A macro, since a material's own
// #includes come before cg_env.glsl declares the frame block: it may only be expanded inside vertex() or fragment().
#define VFX_CAMERA (-(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz))

// ── Noise ──────────────────────────────────────────────────────────────────────────────────────────────────────

float vfx_hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 vfx_hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Value noise in [0, 1], quintic-smoothed.
float vfx_noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = vfx_hash31(i);
    float b = vfx_hash31(i + vec3(1.0, 0.0, 0.0));
    float c = vfx_hash31(i + vec3(0.0, 1.0, 0.0));
    float d = vfx_hash31(i + vec3(1.0, 1.0, 0.0));
    float e = vfx_hash31(i + vec3(0.0, 0.0, 1.0));
    float g = vfx_hash31(i + vec3(1.0, 0.0, 1.0));
    float h = vfx_hash31(i + vec3(0.0, 1.0, 1.0));
    float k = vfx_hash31(i + vec3(1.0, 1.0, 1.0));
    return mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y), mix(mix(e, g, u.x), mix(h, k, u.x), u.y), u.z);
}

// Octaves rotated against each other, so no grid shows through.
const mat3 VFX_OCTAVE = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

float vfx_fbm(vec3 p, int octaves) {
    float sum = 0.0, amp = 0.5, norm = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= octaves) break;
        sum += amp * vfx_noise(p);
        norm += amp;
        p = VFX_OCTAVE * p * 2.03;
        amp *= 0.5;
    }
    return sum / norm;
}

// Sharp ridges where the noise crosses its middle: veins, cracks, lightning.
float vfx_ridged(vec3 p, int octaves) {
    float sum = 0.0, amp = 0.5, norm = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= octaves) break;
        float n = 1.0 - abs(vfx_noise(p) * 2.0 - 1.0);
        sum += amp * n * n;
        norm += amp;
        p = VFX_OCTAVE * p * 2.11;
        amp *= 0.5;
    }
    return sum / norm;
}

// Cellular noise: the nearest and second-nearest feature distances, and the nearest cell's id in [0, 1).
vec3 vfx_voronoi(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    float f1 = 8.0, f2 = 8.0, id = 0.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 offset = vec3(float(x), float(y), float(z));
                vec3 point = offset + vfx_hash33(cell + offset);
                float d = length(point - f);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                    id = vfx_hash31(cell + offset);
                } else if (d < f2) {
                    f2 = d;
                }
            }
        }
    }
    return vec3(f1, f2, id);
}

// The cube face unit vector {@code p} points through, and where on it: xy in [-1, 1], equi-angular so cells keep their
// size; z the face. Neighbouring faces share each edge's coordinate, so a grid of cells lines up across it.
vec3 vfx_cube_face(vec3 p) {
    vec3 a = abs(p);
    vec2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z) {
        uv = vec2(p.z, p.y) / a.x;
        face = p.x > 0.0 ? 0.0 : 1.0;
    } else if (a.y >= a.z) {
        uv = vec2(p.x, p.z) / a.y;
        face = p.y > 0.0 ? 2.0 : 3.0;
    } else {
        uv = vec2(p.x, p.y) / a.z;
        face = p.z > 0.0 ? 4.0 : 5.0;
    }
    return vec3(atan(uv) * 4.0 / 3.14159265, face);
}

// ── Colour ─────────────────────────────────────────────────────────────────────────────────────────────────────

// Narkowicz's fit of the ACES filmic curve: HDR in, display out, highlights rolling off rather than clipping.
vec3 vfx_aces(vec3 x) {
    x *= 0.8;
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Inigo Quilez's cosine palette.
vec3 vfx_palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
    return a + b * cos(6.28318 * (c * t + d));
}

// ── The environment: a dark space studio ───────────────────────────────────────────────────────────────────────

const vec3 VFX_KEY_DIR = vec3(-0.4924, 0.7385, 0.4616);   // normalize(-0.4, 0.6, 0.375)
const vec3 VFX_KEY_COLOR = vec3(3.2, 2.85, 2.4);
const vec3 VFX_RIM_DIR = vec3(0.5937, 0.3299, -0.7337);
const vec3 VFX_RIM_COLOR = vec3(0.55, 1.1, 2.3);
const vec3 VFX_FILL_DIR = vec3(-0.9615, 0.1923, -0.1923);
const vec3 VFX_FILL_COLOR = vec3(1.7, 0.35, 1.25);

// A soft round light panel: 1 inside {@code size} radians of {@code axis}, falling off over {@code soft} more.
float vfx_panel(vec3 d, vec3 axis, float size, float soft) {
    float angle = acos(clamp(dot(d, axis), -1.0, 1.0));
    return 1.0 - smoothstep(size, size + soft, angle);
}

// What a surface sees in direction {@code d}: three light panels over a deep violet sky and a dark floor. Rough
// surfaces see the panels wider and dimmer, as a prefiltered map would give them.
vec3 vfx_env(vec3 d, float roughness) {
    float r = clamp(roughness, 0.0, 1.0);
    float up = d.y;
    vec3 sky = mix(vec3(0.16, 0.07, 0.22), vec3(0.012, 0.02, 0.06), smoothstep(0.0, 0.65, up));
    vec3 floorColor = vec3(0.015, 0.015, 0.02) + vec3(0.05, 0.03, 0.08) * exp(-abs(up) * 8.0);
    vec3 base = up > 0.0 ? sky : floorColor;
    // A horizon line glowing faintly, as the floor's grid seen from far away.
    base += vec3(0.25, 0.12, 0.4) * exp(-abs(up) * 40.0) * (1.0 - r);
    float spread = 1.0 + r * 6.0;
    float dim = 1.0 / (spread * spread);
    vec3 lights = VFX_KEY_COLOR * vfx_panel(d, VFX_KEY_DIR, 0.22 * spread, 0.08 * spread) * dim * 2.5;
    lights += VFX_RIM_COLOR * vfx_panel(d, VFX_RIM_DIR, 0.16 * spread, 0.10 * spread) * dim * 2.0;
    lights += VFX_FILL_COLOR * vfx_panel(d, VFX_FILL_DIR, 0.10 * spread, 0.18 * spread) * dim * 1.6;
    return base + lights;
}

// ── Lighting ───────────────────────────────────────────────────────────────────────────────────────────────────

float vfx_ggx(float nh, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    return a2 / (CG_PI * d * d + 1.0e-6);
}

float vfx_smith(float nv, float nl, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    return (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
}

vec3 vfx_schlick(vec3 f0, float cosTheta) {
    return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
}

// Karis' analytic fit of the split-sum BRDF table.
vec2 vfx_env_brdf(float nv, float roughness) {
    const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 rr = roughness * c0 + c1;
    float a004 = min(rr.x * rr.x, exp2(-9.28 * nv)) * rr.x + rr.y;
    return vec2(-1.04, 1.04) * a004 + rr.zw;
}

vec3 vfx_direct(vec3 n, vec3 v, vec3 l, vec3 radiance, vec3 albedo, float metallic, float roughness) {
    vec3 h = normalize(l + v);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 1.0e-4);
    float nh = max(dot(n, h), 0.0);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec3 f = vfx_schlick(f0, max(dot(h, v), 0.0));
    vec3 spec = vfx_ggx(nh, roughness) * vfx_smith(nv, nl, roughness) * f / (4.0 * nv * nl + 1.0e-4);
    vec3 diffuse = (1.0 - f) * (1.0 - metallic) * albedo / CG_PI;
    return (diffuse + spec) * radiance * nl;
}

// A metal-roughness surface lit by the studio's three panels and its environment.
vec3 vfx_pbr(vec3 n, vec3 v, vec3 albedo, float metallic, float roughness, float occlusion) {
    roughness = clamp(roughness, 0.04, 1.0);
    vec3 color = vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, albedo, metallic, roughness);
    color += vfx_direct(n, v, VFX_RIM_DIR, VFX_RIM_COLOR, albedo, metallic, roughness);
    color += vfx_direct(n, v, VFX_FILL_DIR, VFX_FILL_COLOR * 0.6, albedo, metallic, roughness);
    float nv = max(dot(n, v), 1.0e-4);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec2 ab = vfx_env_brdf(nv, roughness);
    vec3 specular = vfx_env(reflect(-v, n), roughness) * (f0 * ab.x + ab.y);
    vec3 diffuse = vfx_env(n, 1.0) * albedo * (1.0 - metallic) * 0.6;
    return color + (specular + diffuse) * occlusion;
}

// ── The studio a metal mirrors ─────────────────────────────────────────────────────────────────────────────────
// A metal is only what it reflects, so the metals mirror something legible and near neutral, as a product studio is,
// since a coloured room tints a metal out of its own colour: a soft sky over a bright warm horizon, two big softboxes,
// and below, the floor's grid -- the reflected ray is followed down to the plane at {@code floorY} from the shading
// point {@code pos}, so the tiles curve across the sphere as they do on a chrome ball.

vec3 vfx_studio(vec3 pos, vec3 d, float roughness, float floorY) {
    float r = clamp(roughness, 0.0, 1.0);
    float blur = 1.0 + r * 6.0;
    vec3 horizon = vec3(1.3, 1.12, 1.05);
    vec3 sky = mix(vec3(0.5, 0.47, 0.62), vec3(0.08, 0.08, 0.16), smoothstep(0.0, 0.75, d.y));
    sky += horizon * exp(-max(d.y, 0.0) * 14.0 / blur) * 0.7;
    float below = max(-d.y, 1.0e-3);
    float dist = max(pos.y - floorY, 0.05) / below;
    vec2 at = pos.xz + d.xz * dist;
    vec2 cell = abs(fract(at) - 0.5);
    float w = 0.012 + dist * 0.004 + r * 0.08;
    float lines = max(smoothstep(0.5 - w, 0.5, cell.x), smoothstep(0.5 - w, 0.5, cell.y));
    vec3 tiles = vec3(0.42, 0.52, 0.7) * (0.55 + 0.45 * exp(-dist * 0.05));
    vec3 ground = mix(tiles, vec3(0.1, 0.11, 0.16), lines * exp(-dist * 0.08) * 0.6);
    ground = mix(ground, horizon * 0.6, 1.0 - exp(-dist * 0.035));
    vec3 base = mix(ground, sky, smoothstep(-0.03 * blur, 0.03 * blur, d.y));
    float spread = 1.0 + r * 4.0;
    float dim = 1.0 / spread;
    vec3 lights = VFX_KEY_COLOR * vfx_panel(d, VFX_KEY_DIR, 0.42 * spread, 0.1 * spread) * dim * 1.6;
    lights += VFX_RIM_COLOR * vfx_panel(d, VFX_RIM_DIR, 0.3 * spread, 0.12 * spread) * dim * 1.4;
    lights += VFX_FILL_COLOR * vfx_panel(d, VFX_FILL_DIR, 0.18 * spread, 0.16 * spread) * dim;
    return base + lights;
}

// vfx_pbr, mirroring vfx_studio as seen from {@code pos}: what the metals use.
vec3 vfx_pbr_studio(vec3 pos, float floorY, vec3 n, vec3 v, vec3 albedo, float metallic, float roughness) {
    roughness = clamp(roughness, 0.04, 1.0);
    vec3 color = vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, albedo, metallic, roughness);
    color += vfx_direct(n, v, VFX_RIM_DIR, VFX_RIM_COLOR, albedo, metallic, roughness);
    color += vfx_direct(n, v, VFX_FILL_DIR, VFX_FILL_COLOR * 0.6, albedo, metallic, roughness);
    float nv = max(dot(n, v), 1.0e-4);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec2 ab = vfx_env_brdf(nv, roughness);
    vec3 specular = vfx_studio(pos, reflect(-v, n), roughness, floorY) * (f0 * ab.x + ab.y);
    vec3 diffuse = vfx_studio(pos, n, 1.0, floorY) * albedo * (1.0 - metallic) * 0.6;
    return color + specular + diffuse;
}
