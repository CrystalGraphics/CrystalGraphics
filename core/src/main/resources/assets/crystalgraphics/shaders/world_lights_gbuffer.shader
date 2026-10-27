// The world lights' G-buffer (CgWorldLights), Unreal's GBufferA in role: each pixel's normal in the grid's view, written
// once a firing and read by every lighting pass (the apply, MegaLights' sample, shade and denoise) instead of each
// rebuilding it. Ours as the opaque pass drew it (_Normals); Minecraft's from depth, from whichever neighbour each way is
// the nearer step, so a block's edge does not bend it (Wicked Engine's improved normal reconstruction, MIT), held to a
// world axis within 10 degrees, as its blocks are. Half floats, so the normal is the one each pass rebuilt to a
// thousandth. The sky has none: (0, 0, 0). Alpha 1 where the pixel is ours: our opaque pass wrote its eye depth there
// and nothing the host drew after it covers it (a player's translucent skin).
#type none
#include "crystalgraphics:shaders/lib/math.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Rays    ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0 ("Eye-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1 ("The second row",                                    vec4) = (0, 1, 0, 0)
    _ToView2 ("The third row",                                     vec4) = (0, 0, 1, 0)
    _Depth   ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _Normals ("Our surfaces' normals, world, n * 0.5 + 0.5; alpha the eye depth drawn at, negative with no normal", sampler2D) = "black"
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

    vec3 to_view_dir(vec3 w) { return vec3(dot(_ToView0.xyz, w), dot(_ToView1.xyz, w), dot(_ToView2.xyz, w)); }
    vec3 to_world_dir(vec3 v) {
        return vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), v), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), v),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), v));
    }

    // The scene's eye depth under the bob-free perspective; 0 for none (the sky).
    float eye_depth(ivec2 px) {
        px = clamp(px, ivec2(0), ivec2(CG_RESOLUTION) - 1);
        float window = texelFetch(cg_DepthBuffer, px, 0).r;
        if (cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0) return 0.0;
        float ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        return _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
    }

    vec3 surface(ivec2 px) {
        float depth = eye_depth(px);
        vec2 uv = (vec2(clamp(px, ivec2(0), ivec2(CG_RESOLUTION) - 1)) + 0.5) / CG_RESOLUTION;
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
        if (p.z >= 0.0) {
            fragColor = vec4(0.0);
            return;
        }
        // Ours where the opaque pass drew at this depth (Unreal's G-buffer normal).
        vec4 drawn = texelFetch(_Normals, px, 0);
        float depth = -p.z;
        bool ours = drawn.a != 0.0 && abs(abs(drawn.a) - depth) < max(0.02, 0.004 * depth);
        vec3 n;
        if (ours && drawn.a > 0.0) {
            n = to_view_dir(normalize(drawn.xyz * 2.0 - 1.0));
            if (dot(n, p) > 0.0) n = -n;
        } else {
            vec3 left = surface(px - ivec2(1, 0)), right = surface(px + ivec2(1, 0));
            vec3 down = surface(px - ivec2(0, 1)), up = surface(px + ivec2(0, 1));
            vec3 across = abs(left.z - p.z) < abs(right.z - p.z) ? p - left : right - p;
            vec3 rising = abs(down.z - p.z) < abs(up.z - p.z) ? p - down : up - p;
            n = cross(across, rising);
            n = dot(n, n) > 1.0e-20 ? n * inversesqrt(dot(n, n)) : normalize(-p);
            if (dot(n, p) > 0.0) n = -n;
            // Depth's precision tilts a rebuilt normal row to row on far grazing ground, which a low light's N.L turns
            // into stripes.
            vec3 world = to_world_dir(n), size = abs(world);
            float most = max(size.x, max(size.y, size.z));
            if (most > 0.985) n = to_view_dir(sign(world) * step(vec3(most), size));
        }
        fragColor = vec4(n, ours ? 1.0 : 0.0);
    }
}
