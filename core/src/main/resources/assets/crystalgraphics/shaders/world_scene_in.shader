// The HDR scene's first pass: the host's 8-bit colour decoded into the linear scene texel for texel. With DEPTH (a host
// that lends no depth: framebuffer 0, multisampled) it also writes the host's depth into the scene's own, under a
// depth state the caller sets. With KEEP (the transparent firing after an opaque firing that drew into a scene of its
// own) a pixel still holding exactly what that scene encoded keeps the scene's value, past white included; one the host
// drew over since is decoded. CgSceneTarget draws it; nothing else should.
#type none
#pragma cg_feature DEPTH
#pragma cg_feature KEEP
#pragma cg_permutation DEPTH
#pragma cg_permutation KEEP
#include "crystalgraphics:shaders/lib/post/composite.glsl"

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" "SceneColorMargin" = "0.001" }
Queue = "Overlay"

Properties {
    _Kept ("The opaque firing's scene", sampler2D) = "black"
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

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 at = ivec2(gl_FragCoord.xy);
        vec4 host = texelFetch(cg_SceneColor, at, 0);
        fragColor = vec4(post_decode_srgb(host.rgb), host.a);
#ifdef KEEP
        // world_scene_out wrote the code nearest encode(min(kept, 1)) into these bytes: still it, nothing drew since
        vec4 kept = texelFetch(_Kept, at, 0);
        vec3 written = post_encode_srgb(min(kept.rgb, vec3(1.0))) * 255.0;
        if (all(lessThanEqual(abs(host.rgb * 255.0 - written), vec3(0.51)))) fragColor = kept;
#endif
#ifdef DEPTH
        gl_FragDepth = texelFetch(cg_DepthBuffer, at, 0).r;
#endif
    }
}
