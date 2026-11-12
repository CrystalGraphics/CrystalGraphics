#type none
#include "crystalgraphics:shaders/lib/post/post_material.glsl"

// The harness's post_chain, its targets named as the format names a step's.

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.01" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Target" }
Queue = "Overlay"

Properties {
    _Step_Down ("The scene halved, at half size", sampler2D) = "black"
    _Step_Quarter ("That plus a quarter, at a quarter size", sampler2D) = "black"
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Down" "Target" = "_Step_Down" "Scale" = "0.5" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }
    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }
    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(CG_SCENE_COLOR(i.uv).rgb * 0.5, 1.0);
    }
}

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Quarter" "Target" = "_Step_Quarter" "Scale" = "0.25" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }
    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }
    void fragment(in v2f i, out vec4 fragColor) {
        bool sized = textureSize(_Step_Down, 0).x == int(CG_RESOLUTION.x * 0.5);
        fragColor = sized ? vec4(texture(_Step_Down, i.uv).rgb + 0.25, 1.0) : vec4(1.0, 0.0, 1.0, 1.0);
    }
}

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Over" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }
    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }
    void fragment(in v2f i, out vec4 fragColor) {
        bool sized = textureSize(_Step_Quarter, 0).x == int(CG_RESOLUTION.x * 0.25);
        fragColor = sized ? vec4(texture(_Step_Quarter, i.uv).rgb, 1.0) : vec4(1.0, 0.0, 1.0, 1.0);
    }
}
