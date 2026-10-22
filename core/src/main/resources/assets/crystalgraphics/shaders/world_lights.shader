// The world's lights (CgWorldLights) lighting the scene behind each pixel: the surface rebuilt from depth, its light
// grid cell found as the binning placed it, and each of the cell's lights, a point, a spot or a tube, attenuated as
// Unreal's local lights are with no surface (lights.glsl's cg_light_falloff). Unreal multiplies light by the surface's
// diffuse colour; Minecraft keeps none, so the scene's own colour stands in for it: the pixel becomes
// scene × (1 + light). There is no facing either: a normal rebuilt from depth flickers.
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
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

struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; };

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

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        // cg_LinearEyeDepth under the bob-free perspective (CgWorldLights.splitPerspective).
        float window = texture(cg_DepthBuffer, uv).r, ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        float depth = _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
        vec3 p = vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth);
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int n = int(CELLS(e * 2)), from = int(CELLS(e * 2 + 1));
        vec3 sum = vec3(0.0);
        for (int k = 0; k < n; k++) {
            CgLight light = LIGHTS(int(INDICES(from + k)));
            vec3 at = light.positionRadius.xyz, way = light.spotCos.xyz, along = light.segmentCone.xyz;
            vec3 v = vec3(dot(_ToView0.xyz, at), dot(_ToView1.xyz, at), dot(_ToView2.xyz, at))
                    + vec3(_ToView0.w, _ToView1.w, _ToView2.w);
            vec4 spot = vec4(dot(_ToView0.xyz, way), dot(_ToView1.xyz, way), dot(_ToView2.xyz, way), light.spotCos.w);
            vec4 segment = vec4(dot(_ToView0.xyz, along), dot(_ToView1.xyz, along), dot(_ToView2.xyz, along), light.segmentCone.w);
            sum += light.colorExponent.rgb
                    * cg_light_falloff(v, light.positionRadius.w, light.colorExponent.w, spot, segment, p);
        }
        fragColor = vec4(sum, 0.0);
    }
}
