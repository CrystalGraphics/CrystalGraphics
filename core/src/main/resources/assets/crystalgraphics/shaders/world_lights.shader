// The world's lights (CgWorldLights) lighting the scene behind each pixel, as Unreal lights a surface with a simple light
// (DeferredLightingCommon.ush's GetSimpleDynamicLighting, its diffuse half): colour × attenuation × N·L × albedo / π.
// Minecraft keeps neither albedo nor normals, so both are rebuilt. The surface and its normal come from depth, the
// normal from whichever neighbour each way is the nearer step, so a block's edge does not bend it (Wicked Engine's
// improved normal reconstruction, MIT), held to a world axis near one, as Minecraft's blocks are. The albedo is the scene's colour over the lightmap Minecraft lit it by, read
// at the light's own block (CgWorldLights' ambient): what the light adds is scene × light / lightmap, which the blend
// does. Minecraft has no exposure, so its noon is as bright as its moonlight is dim; a light is adapted to the sun as
// an eye would be, for the lights alone: full at night and wherever no sky reaches it, fading to _DayFade under the
// full daylight of an open sky (CG_DAYLIGHT times the sky light at it). Specular is left out: Minecraft has no
// roughness. A light that casts shadows is not lit here while the shadow passes run (_Shadows): their denoised lighting,
// in the same units, is added in its place (_Shadowed, CgWorldLights' MegaLights port).
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/lights.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" "Media" = "Off" }
Queue = "Transparent"

Properties {
    _Grid    ("Cells across, up and deep",                         vec4) = (1, 1, 32, 0)
    _Tile    ("A tile's share of the target across and up",        vec4) = (1, 1, 0, 0)
    _ZParams ("The slices' B, O and S",                            vec4) = (1, 1, 1, 0)
    _Rays    ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0 ("Camera-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1 ("The second row",                                    vec4) = (0, 1, 0, 0)
    _ToView2 ("The third row",                                     vec4) = (0, 0, 1, 0)
    _Depth   ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _DayFade ("A light's strength under a full day's open sky; 1 for never fading", float) = 0.1
    _Shadows ("1 while the shadow passes light the lights that cast shadows", float) = 0.0
    _Shadowed ("Their lighting, denoised",                         sampler2D) = "black"
}

struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; };

Buffers {
    LIGHTS  ("The firing's lights",            CgLight, readonly)
    CELLS   ("Each cell's light count and start", uint, readonly)
    INDICES ("The cells' light indices",       uint,    readonly)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend DST_COLOR ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // The surface at pixel px, in the grid's view: cg_LinearEyeDepth under the bob-free perspective
    // (CgWorldLights.splitPerspective), along the pixel's ray.
    vec3 surface(ivec2 px) {
        px = clamp(px, ivec2(0), ivec2(CG_RESOLUTION) - 1);
        float window = texelFetch(cg_DepthBuffer, px, 0).r, ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        float depth = _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
        vec2 uv = (vec2(px) + 0.5) / CG_RESOLUTION;
        return vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth);
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 px = ivec2(gl_FragCoord.xy);
        vec3 p = surface(px);
        // Each way, the side whose depth steps less: across a block's edge the other side is another surface.
        vec3 left = surface(px - ivec2(1, 0)), right = surface(px + ivec2(1, 0));
        vec3 down = surface(px - ivec2(0, 1)), up = surface(px + ivec2(0, 1));
        vec3 across = abs(left.z - p.z) < abs(right.z - p.z) ? p - left : right - p;
        vec3 rising = abs(down.z - p.z) < abs(up.z - p.z) ? p - down : up - p;
        vec3 n = cross(across, rising);
        // No plane where the neighbours give none (the sky's far plane): face the eye.
        n = dot(n, n) > 1.0e-20 ? n * inversesqrt(dot(n, n)) : normalize(-p);
        if (dot(n, p) > 0.0) n = -n;
        // Minecraft's terrain is axis-aligned: a normal within 10 degrees of a world axis is that axis. Depth's precision
        // tilts a rebuilt normal row to row on far grazing ground, which a low light's N.L turns into stripes.
        vec3 world = vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), n), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), n),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), n));
        vec3 size = abs(world);
        float most = max(size.x, max(size.y, size.z));
        if (most > 0.985) {
            world = sign(world) * step(vec3(most), size);
            n = vec3(dot(_ToView0.xyz, world), dot(_ToView1.xyz, world), dot(_ToView2.xyz, world));
        }
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int count = int(CELLS(e * 2)), from = int(CELLS(e * 2 + 1));
        // The darkest lightmap the albedo is recovered from: below it the scene's noise would be lit as colour.
        float floorLight = CG_LINEAR_SCENE ? 0.02 : 0.15;
        vec3 sum = vec3(0.0);
        bool shadows = _Shadows > 0.5;
        for (int k = 0; k < count; k++) {
            CgLight light = LIGHTS(int(INDICES(from + k)));
            if (shadows && CG_LIGHT_SHADOWED(light)) continue;
            vec3 at = light.positionRadius.xyz, way = light.spotCos.xyz, along = light.segmentCone.xyz;
            vec3 v = vec3(dot(_ToView0.xyz, at), dot(_ToView1.xyz, at), dot(_ToView2.xyz, at))
                    + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
            vec4 spot = vec4(dot(_ToView0.xyz, way), dot(_ToView1.xyz, way), dot(_ToView2.xyz, way), light.spotCos.w);
            vec4 segment = vec4(dot(_ToView0.xyz, along), dot(_ToView1.xyz, along), dot(_ToView2.xyz, along), light.segmentCone.w);
            float lit = cg_light_surface(v, light.positionRadius.w, light.colorExponent.w, spot, segment, light.ambient.z, p, n);
            if (lit <= 0.0) continue;
            vec3 ambient = vec3(1.0);
            float adapt = 1.0;
            if (light.ambient.x >= 0.0) {
                vec3 lightmap = CG_LIGHTMAP(light.ambient.xy);
                adapt = mix(1.0, _DayFade, clamp(CG_DAYLIGHT, 0.0, 1.0) * light.ambient.y / 15.0);
                ambient = max(CG_LINEAR_SCENE ? srgb_to_linear(lightmap) : lightmap, vec3(floorLight));
            }
            sum += light.colorExponent.rgb * (lit * adapt / CG_PI) / ambient;
        }
        if (shadows) sum += texelFetch(_Shadowed, px, 0).rgb;
        fragColor = vec4(sum, 0.0);
    }
}
