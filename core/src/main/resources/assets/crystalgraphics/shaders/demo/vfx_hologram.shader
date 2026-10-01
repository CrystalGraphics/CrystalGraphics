// A hologram: cyan light only, scanlines climbing, a lattice seen through both walls, slices of it glitching sideways.
// CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        float t = CG_TIME;
        vec3 p = cg_Position;
        // A glitch: now and then a band of the sphere jumps sideways for a few frames.
        float band = floor(p.y * 9.0);
        float tick = floor(t * 11.0);
        float jump = step(0.9, vfx_hash31(vec3(band, tick, 3.0)));
        p.x += jump * (vfx_hash31(vec3(band, tick, 7.0)) - 0.5) * 0.35;
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = abs(dot(n, v));
        float rim = pow(1.0 - nv, 2.5);
        float scan = 0.55 + 0.45 * sin((i.worldPos.y - t * 0.9) * 110.0);
        float sweep = exp(-pow(fract(i.worldPos.y * 0.35 - t * 0.4) - 0.5, 2.0) * 220.0);
        // The lattice: latitude and longitude lines.
        float lon = atan(i.objPos.z, i.objPos.x) / 6.28318;
        float lat = asin(clamp(i.objPos.y, -1.0, 1.0)) / 3.14159;
        vec2 grid = abs(fract(vec2(lon * 24.0, lat * 12.0)) - 0.5);
        float lines = 1.0 - smoothstep(0.0, 0.06, min(grid.x, grid.y));
        float flicker = 0.85 + 0.15 * vfx_noise(vec3(t * 20.0, 0.0, 0.0));
        vec3 cyan = vec3(0.25, 0.85, 1.25);
        vec3 color = cyan * (0.06 + rim * 1.4 + lines * 0.55 + sweep * 1.2) * scan * flicker;
        if (!gl_FrontFacing) color *= 0.45;
        fragColor = vec4(color, 1.0);
    }
}
