// The world's lights (CgWorldLights) lighting the scene behind each pixel, as Unreal lights a surface with a simple light
// (DeferredLightingCommon.ush's GetSimpleDynamicLighting, its diffuse half): colour × attenuation × N·L × albedo / π.
// Minecraft keeps neither albedo nor normals, so both are rebuilt. The surface and its normal come from depth, the
// normal from whichever neighbour each way is the nearer step, so a block's edge does not bend it (Wicked Engine's
// improved normal reconstruction, MIT). The albedo is the scene's colour over the lightmap Minecraft lit it by, read
// at the light's own block (CgWorldLights' ambient): what the light adds is scene × light / lightmap, which the blend
// does, so a light reads as strong at night as by day. Specular is left out: Minecraft has no roughness to give it.
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
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int count = int(CELLS(e * 2)), from = int(CELLS(e * 2 + 1));
        // The darkest lightmap the albedo is recovered from: below it the scene's noise would be lit as colour.
        float floorLight = CG_LINEAR_SCENE ? 0.02 : 0.15;
        vec3 sum = vec3(0.0);
        for (int k = 0; k < count; k++) {
            CgLight light = LIGHTS(int(INDICES(from + k)));
            vec3 at = light.positionRadius.xyz, way = light.spotCos.xyz, along = light.segmentCone.xyz;
            vec3 v = vec3(dot(_ToView0.xyz, at), dot(_ToView1.xyz, at), dot(_ToView2.xyz, at))
                    + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
            vec4 spot = vec4(dot(_ToView0.xyz, way), dot(_ToView1.xyz, way), dot(_ToView2.xyz, way), light.spotCos.w);
            vec4 segment = vec4(dot(_ToView0.xyz, along), dot(_ToView1.xyz, along), dot(_ToView2.xyz, along), light.segmentCone.w);
            float falloff = cg_light_falloff(v, light.positionRadius.w, light.colorExponent.w, spot, segment, p);
            if (falloff <= 0.0) continue;
            // Toward the nearest point of its line (its centre, for a point or a spot).
            float t = dot(segment.xyz, segment.xyz) > 0.0 ? clamp(dot(p - v, segment.xyz) / dot(segment.xyz, segment.xyz), -0.5, 0.5) : 0.0;
            vec3 toLight = v + segment.xyz * t - p;
            float nol = clamp(dot(n, toLight) * inversesqrt(max(dot(toLight, toLight), 1.0e-12)), 0.0, 1.0);
            vec3 ambient = vec3(1.0);
            if (light.ambient.x >= 0.0) {
                ambient = CG_LIGHTMAP(light.ambient.xy);
                if (CG_LINEAR_SCENE) ambient = srgb_to_linear(ambient);
                ambient = max(ambient, vec3(floorLight));
            }
            sum += light.colorExponent.rgb * (falloff * nol / CG_PI) / ambient;
        }
        fragColor = vec4(sum, 0.0);
    }
}
