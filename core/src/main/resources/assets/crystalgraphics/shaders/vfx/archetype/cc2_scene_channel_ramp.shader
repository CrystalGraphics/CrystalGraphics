// CC2's scene ramp per channel (NSUNS4 0001f012, research nsuns4 VFX_ENGINE.md §4 "scene"): each channel of the scene
// behind it through its row of a ramp (_Tex1, SECOND_LAYER), shown where _Tex0's alpha on _UV0Window covers.
#type spatial
#pragma cg_feature SECOND_LAYER

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Tex0      ("Mask", sampler2D) = "white"
    _Tex1      ("Ramp: rows red, green, blue", sampler2D) = "white"
    _UV0Window ("Mask UV: offset xy, scale zw", vec4) = (0, 0, 1, 1)
    _Glare     ("How much glows, 0..1", float) = 0.0
    _Clip      ("The alpha it must pass, 0..1", float) = 0.0
    _OutlineID ("Its outline group", float) = 1.0
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
#ifdef SECOND_LAYER
        vec3 c = vec3(texture(_Tex1, vec2(scene.r, 1.0 / 6.0)).r, texture(_Tex1, vec2(scene.g, 0.5)).g,
                texture(_Tex1, vec2(scene.b, 5.0 / 6.0)).b);
#else
        vec3 c = scene;
#endif
        float a = texture(_Tex0, i.uv0).a * i.colour.a;
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
