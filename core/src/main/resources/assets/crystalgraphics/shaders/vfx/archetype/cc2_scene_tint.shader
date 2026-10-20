// CC2's scene tint (NSUNS4 0001f011, research nsuns4 VFX_ENGINE.md §4 "scene"): the scene behind it times its vertex
// colour, faded by the vertex alpha.
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _UV0Window ("Unused by this program", vec4) = (0, 0, 1, 1)
    _Glare     ("How much glows, 0..1", float) = 0.0
    _Clip      ("The alpha it must pass, 0..1", float) = 0.0
}

struct v2f { vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
    }

    void vertex(out v2f o) {
        o.colour = cg_Color;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 c = CG_SCENE_COLOR(gl_FragCoord.xy / CG_RESOLUTION).rgb * i.colour.rgb;
        float a = i.colour.a;
        if (a <= _Clip) discard;
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
