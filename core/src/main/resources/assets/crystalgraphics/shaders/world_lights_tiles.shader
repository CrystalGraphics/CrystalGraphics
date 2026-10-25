// The entities a shadow ray from each 16x16 tile can meet, as Unreal culls capsule shadows per tile
// (CapsuleShadowShaders.usf:173-480): the tile's depth range, a sphere round its slice of the view, and every entity
// whose bounding sphere comes within the tile's rays: the farthest a light's ray runs (_Reach.x), or along the sun for
// the world trace's length. A pixel's rays then test only its tile's entities (world_lights_sample.shader).
//
// Written: a bit an entity, 16 a channel, each channel's bits as a whole float (exact to 2^24).
#type none
#include "crystalgraphics:shaders/lib/math.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Screen   ("The full target's width and height",                vec4) = (1, 1, 0, 0)
    _Rays     ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0  ("Eye-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1  ("The second row",                                     vec4) = (0, 1, 0, 0)
    _ToView2  ("The third row",                                      vec4) = (0, 0, 1, 0)
    _Depth    ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _EyeFrac  ("The eye within its block",                           vec4) = (0, 0, 0, 0)
    _Reach    ("The farthest a light's ray runs, blocks; 1 with the sun; the sun's ray's length", vec4) = (0, 0, 96, 0)
    _Entities ("Entity boxes in ENTITIES, at most 64",               int) = 0
    _Ranges   ("Each 4x4 pixels' window depth range (world_lights_tile_depth)", sampler2D) = "black"
}

Buffers {
    ENTITIES ("Entity boxes, two vec4s each: min and max from the eye's block", vec4, readonly)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    const int TILE = 16;

    vec3 to_world(vec3 v) {
        v -= vec3(_ToView0.w, _ToView1.w, _ToView2.w);
        return vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), v), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), v),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), v));
    }

    // world_lights_sample.shader's: a window depth's eye depth under the bob-free perspective; 0 for none (the sky).
    float eye_depth_of(float window) {
        if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return 0.0;
        float ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        return _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
    }

    vec3 at_depth(vec2 uv, float depth) { return vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth); }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
        ivec2 tile = ivec2(gl_FragCoord.xy), size = ivec2(_Screen.xy), first = tile * TILE;
        // The window depths' range, the sky left out, from its 4x4 pixels' ranges: 16 fetches, where 256 in a row left
        // the pass waiting on them. Eye depth rises with window depth (falls, reversed), so only the two ends are
        // converted.
        bool reversed = cg_DepthParams.x > 0.5;
        float lo = 2.0, hi = -1.0;
        ivec2 ranges = textureSize(_Ranges, 0);
        for (int y = 0; y < TILE / 4; y++) {
            for (int x = 0; x < TILE / 4; x++) {
                ivec2 q = tile * (TILE / 4) + ivec2(x, y);
                if (q.x >= ranges.x || q.y >= ranges.y) continue;
                vec2 range = texelFetch(_Ranges, q, 0).xy;
                lo = min(lo, range.x);
                hi = max(hi, range.y);
            }
        }
        if (hi < lo) return;
        float near = eye_depth_of(reversed ? hi : lo), far = eye_depth_of(reversed ? lo : hi);
        if (far <= 0.0) return;
        // The tile's slice of the view between its nearest and farthest depth, and the sphere round its corners.
        vec2 uv0 = vec2(first) / _Screen.xy, uv1 = min(vec2(first + TILE) / _Screen.xy, vec2(1.0));
        vec3 corners[8];
        vec3 centre = vec3(0.0);
        for (int c = 0; c < 8; c++) {
            corners[c] = at_depth(vec2((c & 1) == 0 ? uv0.x : uv1.x, (c & 2) == 0 ? uv0.y : uv1.y), (c & 4) == 0 ? near : far);
            centre += corners[c] * 0.125;
        }
        float radius = 0.0;
        for (int c = 0; c < 8; c++) radius = max(radius, distance(corners[c], centre));
        vec3 mid = to_world(centre);
        vec3 toSun = normalize(CG_SUN_DIRECTION);
        bool sun = _Reach.y > 0.5 && CG_SUN_DIRECTION.y > 0.0;
        uvec4 mask = uvec4(0u);
        for (int k = 0; k < min(_Entities, 64); k++) {
            vec3 lo = ENTITIES(k * 2).xyz - _EyeFrac.xyz, hi = ENTITIES(k * 2 + 1).xyz - _EyeFrac.xyz;
            vec3 e = (lo + hi) * 0.5;
            // The rays start off the surface by the sample pass's normal bias, 0.02 + 0.002 of the depth: past the tile.
            float reach = radius + 0.5 * length(hi - lo) + 0.05 + 0.002 * far;
            bool keep = distance(e, mid) <= reach + _Reach.x;
            if (!keep && sun) keep = distance(e, mid + toSun * clamp(dot(e - mid, toSun), 0.0, _Reach.z)) <= reach;
            if (keep) mask[k >> 4] |= 1u << uint(k & 15);
        }
        fragColor = vec4(mask);
    }
}
