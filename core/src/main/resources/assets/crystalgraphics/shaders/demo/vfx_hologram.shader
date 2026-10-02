// A hologram of a planet: continents in glowing dots, a faint lattice and two orbit rings, light only and both walls
// seen; scanlines climb it, and now and then a band glitches sideways with its colours split. The floor beneath has its
// projector. CgVfxShowcase.
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

    // The glitch at height {@code y}: 1 for the few frames a band jumps, else 0; its offset in {@code shift}.
    float hologram_glitch(float y, float t, out float shift) {
        float band = floor(y * 9.0);
        float tick = floor(t * 11.0);
        shift = fx_hash31(vec3(band, tick, 7.0)) - 0.5;
        return step(0.9, fx_hash31(vec3(band, tick, 3.0)));
    }

    // The planet at longitude {@code lon} and latitude {@code lat} (turns, in [-0.5, 0.5]): a dot on each cell of an
    // even dot grid whose centre lies on land.
    float hologram_globe(float lon, float lat) {
        float rows = 44.0;
        float row = floor((lat + 0.5) * rows);
        float rowLat = (row + 0.5) / rows - 0.5;
        float perRow = max(floor(rows * 2.0 * cos(rowLat * 3.14159265)), 1.0);
        float column = floor((lon + 0.5) * perRow);
        float dotLon = (column + 0.5) / perRow - 0.5;
        vec2 inCell = vec2(fract((lon + 0.5) * perRow), fract((lat + 0.5) * rows)) - 0.5;
        float dotShape = smoothstep(0.34, 0.2, length(inCell));
        float a = dotLon * 6.2831853, b = rowLat * 3.14159265;
        vec3 at = vec3(cos(b) * cos(a), sin(b), cos(b) * sin(a));
        float land = smoothstep(0.5, 0.53, fx_value_fbm(at * 1.7 + vec3(11.0, 3.0, 5.0), 5));
        return dotShape * land;
    }

    void vertex(out v2f o) {
        float t = CG_TIME;
        vec3 p = cg_Position;
        float shift;
        p.x += hologram_glitch(p.y, t, shift) * shift * 0.35;
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(FX_CAMERA - i.worldPos);
        float nv = abs(dot(n, v));
        vec3 p = normalize(i.objPos);
        float lon = atan(p.z, p.x) / 6.2831853;
        float lat = asin(clamp(p.y, -1.0, 1.0)) / 3.14159265;
        // A slight split between the colours always, a wide one in a glitching band.
        float shift;
        float split = 0.003 + hologram_glitch(p.y, t, shift) * 0.02;
        vec3 dots = vec3(hologram_globe(lon + split, lat), hologram_globe(lon, lat), hologram_globe(lon - split, lat));
        // The lattice, faint, and two orbit rings with dashes running round them.
        vec2 grid = abs(fract(vec2(lon * 24.0, lat * 12.0)) - 0.5);
        float lattice = 1.0 - smoothstep(0.0, 0.05, min(grid.x, grid.y));
        float rings = 0.0;
        for (int k = 0; k < 2; k++) {
            vec3 axis = normalize(k == 0 ? vec3(0.35, 1.0, 0.15) : vec3(-0.5, 1.0, -0.3));
            float band = 1.0 - smoothstep(0.008, 0.025, abs(dot(p, axis)));
            vec3 side = normalize(cross(axis, vec3(0.0, 0.0, 1.0)));
            float around = atan(dot(p, cross(axis, side)), dot(p, side)) / 6.2831853;
            float dashes = step(0.45, fract(around * 30.0 - t * (k == 0 ? 0.6 : -0.45)));
            rings += band * (0.35 + 0.65 * dashes);
        }
        float rim = pow(1.0 - nv, 2.5);
        float scan = 0.6 + 0.4 * sin((i.worldPos.y - t * 0.9) * 110.0);
        float sweep = exp(-pow(fract(i.worldPos.y * 0.35 - t * 0.4) - 0.5, 2.0) * 220.0);
        float flicker = 0.85 + 0.15 * fx_value_noise(vec3(t * 20.0, 0.0, 0.0));
        vec3 cyan = vec3(0.25, 0.85, 1.25);
        vec3 color = cyan * (0.04 + rim * 1.3 + lattice * 0.22 + rings * 0.9 + sweep * 1.1)
                + dots * vec3(0.55, 1.05, 1.45) * 1.25;
        color *= scan * flicker;
        if (!gl_FrontFacing) color *= 0.4;
        fragColor = vec4(color, 1.0);
    }
}
