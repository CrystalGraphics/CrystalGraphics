// A Minecraft entity's glowing layer (eyes, glowmasks): vanilla's rendertype_eyes. The texture times the colour, no
// diffuse and no lightmap, added over what is behind with no depth write; the fog fades it out (additive fog mode).
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }

Properties {
    _MainTex ("The glow texture, nearest-filtered; black adds nothing", sampler2D) = "black"
    _Tint    ("The contract's tint: multiplies the glow, by its alpha", color) = (1, 1, 1, 1)
}

struct v2f { vec2 uv; vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
    }

    void vertex(out v2f o) {
        o.uv = cg_TexCoord0;
        o.colour = cg_Color;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texture(_MainTex, i.uv) * i.colour;
        fragColor.rgb *= mix(vec3(1.0), _Tint.rgb, clamp(_Tint.a, 0.0, 1.0));
    }
}
