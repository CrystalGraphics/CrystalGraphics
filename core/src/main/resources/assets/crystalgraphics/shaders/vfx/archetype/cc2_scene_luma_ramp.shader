// CC2's scene ramp by luma (NSUNS4 0001f013, research nsuns4 VFX_ENGINE.md §4 "scene"): the scene behind it recoloured
// through a ramp (_Tex1, SECOND_LAYER) by its luma, shown where _Tex0's alpha on _UV0Window covers.
#type spatial
#pragma cg_feature SECOND_LAYER
#pragma cg_feature SOFT_PARTICLE

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Tex0          ("Mask", sampler2D) = "white"
    _Tex1          ("Ramp by luma", sampler2D) = "white"
    _UV0Window     ("Mask UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _UV1Window     ("Ramp row: y", vec4) = (0, 0, 1, 1)
    _UvScaleNormal ("Unused by this program", vec4) = (1, 1, 0, 0)
    _UvScaleAlpha  ("Mask: x its strength", vec4) = (1, 0, 0, 0)
    _Glare         ("How much glows, 0..1", float) = 0.0
    _Clip          ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID     ("Its outline group", float) = 1.0
}

struct v2f { vec2 uv0; vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
    }

    void vertex(out v2f o) {
        o.uv0 = cg_TexCoord0 * _UV0Window.zw + _UV0Window.xy;
        o.colour = cg_Color;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 scene = CG_SCENE_COLOR(gl_FragCoord.xy / CG_RESOLUTION).rgb;
        float luma = dot(scene, vec3(0.30, 0.59, 0.11));
#ifdef SECOND_LAYER
        vec3 c = texture(_Tex1, vec2(luma, _UV1Window.y)).rgb;
#else
        vec3 c = vec3(luma);
#endif
        float a = texture(_Tex0, i.uv0).a * _UvScaleAlpha.x * i.colour.a;
#ifdef SOFT_PARTICLE
        a *= clamp(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION) - cg_LinearEyeDepth(gl_FragCoord.z), 0.0, 1.0);
#endif
        if (a <= _Clip) discard;
        c *= i.colour.rgb;
#if CG_FOG_MODE == 0
        fragColor = vec4(c, a);
#else
        fragColor = vec4(c * a, a);
#endif
        CG_GLOW(c * a * _Glare);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
    }
}
