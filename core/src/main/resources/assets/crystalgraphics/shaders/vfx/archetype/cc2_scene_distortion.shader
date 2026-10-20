// CC2's scene distortion (NSUNS4 0001f009, research nsuns4 VFX_ENGINE.md §4 "scene"): bends what is behind it by a
// normal map, _Tex0 on _UV0Window scaled by _UvScaleNormal.xy, masked by _Tex1's alpha on _UV1Window times
// _UvScaleAlpha.x. Draws nothing in the scene itself: its Distortion pass writes the offset the world renderer applies.
#type spatial
#pragma cg_feature SOFT_PARTICLE

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Tex0          ("Normal map", sampler2D) = "white"
    _Tex1          ("Mask", sampler2D) = "white"
    _UV0Window     ("Normal UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV1Window     ("Mask UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UvScaleNormal ("Bend: xy its strength in UV units", vec4) = (0.02, 0.02, 0, 0)
    _UvScaleAlpha  ("Mask: x its strength", vec4) = (1, 0, 0, 0)
    _Glare         ("How much glows, 0..1", float) = 0.0
    _Clip          ("The mask it must pass, 0..1", float) = 0.0
    _OutlineID     ("Its outline group", float) = 1.0
}

struct v2f { vec2 uv0; vec2 uv1; vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        ColorMask 0
        DepthTest LEQUAL
        DepthWrite OFF
    }

    void vertex(out v2f o) {
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.uv1 = cg_TexCoord0 * _UV1Window.zw + _UV1Window.xy;
        o.colour = cg_Color;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
    }
}

Pass {
    Tags { "LightMode" = "Distortion" }

    void fragment(in v2f i, out vec4 offset) {
        float mask = texture(_Tex1, i.uv1).a * _UvScaleAlpha.x * i.colour.a;
        if (mask <= _Clip) discard;
#ifdef SOFT_PARTICLE
        float behind = CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) - cg_LinearEyeDepth(gl_FragCoord.z);
        mask *= clamp(behind, 0.0, 1.0);
#endif
        vec2 bend = (texture(_Tex0, i.uv0).xy * 2.0 - 1.0) * _UvScaleNormal.xy * mask;
        offset = CG_DISTORTION(bend, 0.0, cg_LinearEyeDepth(gl_FragCoord.z));
    }
}
