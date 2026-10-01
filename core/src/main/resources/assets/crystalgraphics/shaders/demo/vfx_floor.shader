// The showcase's floor: dark gloss reflecting the studio, a neon grid fading into the distance, a soft shadow under
// each sphere and a pool of coloured light under every glowing one. The sixteen spheres' places and glow colours are
// CgVfxShowcase's grid and table, mirrored here: change one with the other. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

Properties {
    _Grid ("Grid origin x, z, spacing, sphere height", vec4) = (0.0, 0.0, 2.8, 1.35)
}

struct v2f { vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // CgVfxShowcase.GLOW, in the same order: rgb and intensity.
    vec4 floor_glow(int k) {
        if (k == 4) return vec4(0.6, 0.8, 1.0, 0.15);
        if (k == 6) return vec4(0.2, 0.8, 1.2, 0.6);
        if (k == 7) return vec4(0.3, 0.6, 1.2, 0.35);
        if (k == 8) return vec4(1.2, 0.3, 1.4, 1.0);
        if (k == 9) return vec4(0.4, 0.7, 1.6, 0.8);
        if (k == 10) return vec4(1.6, 0.4, 0.05, 0.9);
        if (k == 11) return vec4(2.0, 1.1, 0.3, 1.4);
        if (k == 12) return vec4(1.5, 0.7, 0.25, 0.5);
        if (k == 13) return vec4(0.5, 0.4, 1.4, 0.5);
        if (k == 14) return vec4(0.3, 0.6, 1.6, 0.6);
        if (k == 15) return vec4(0.2, 0.9, 1.6, 0.6);
        return vec4(0.0);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 camera = VFX_CAMERA;
        vec3 v = normalize(camera - i.worldPos);
        vec3 n = vec3(0.0, 1.0, 0.0);
        vec2 xz = i.worldPos.xz;
        float viewDistance = length(camera - i.worldPos);
        // The grid: lit tiles between dark lines a metre apart, a wave pulsing outward from the centre, fading with distance.
        vec2 cell = abs(fract(xz) - 0.5);
        vec2 width = fwidth(xz) * 1.1;
        float fine = 1.0 - smoothstep(0.5 - width.x * 1.5, 0.5, max(cell.x, cell.y));
        vec2 major = abs(fract(xz * 0.25) - 0.5);
        float strong = 1.0 - smoothstep(0.5 - width.x * 0.5, 0.5, max(major.x, major.y));
        float pulse = exp(-pow(fract(length(xz) * 0.08 - t * 0.25) - 0.5, 2.0) * 60.0);
        float fade = exp(-viewDistance * 0.045);
        vec3 grid = vec3(0.35, 0.15, 0.9) * fine * 0.35 + vec3(0.2, 0.75, 1.5) * strong * (0.45 + pulse * 1.5);
        // The spheres: soft shadows under each, coloured light under the glowing ones.
        float shade = 1.0;
        vec3 pools = vec3(0.0);
        for (int k = 0; k < 16; k++) {
            vec2 centre = _Grid.xy + (vec2(float(k - (k / 4) * 4), float(k / 4)) - 1.5) * _Grid.z;
            float d2 = dot(xz - centre, xz - centre);
            shade *= 1.0 - 0.65 * exp(-d2 * 1.6);
            vec4 glow = floor_glow(k);
            pools += glow.rgb * glow.a * exp(-d2 * 0.45) * 0.9;
            // The hologram's projector: a bright ring on the floor beneath it, pulsing.
            if (k == 6) {
                float d = sqrt(d2);
                float ring = exp(-pow((d - 0.42) * 30.0, 2.0)) + exp(-pow((d - 0.22) * 40.0, 2.0)) * 0.6;
                pools += vec3(0.3, 1.0, 1.5) * ring * (0.8 + 0.2 * sin(t * 6.0));
            }
        }
        float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        vec3 reflection = vfx_env(reflect(-v, n), 0.12) * mix(0.15, 1.0, fresnel);
        vec3 base = vec3(0.012, 0.012, 0.018);
        vec3 color = (base + reflection) * shade + grid * fade * shade + pools;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
