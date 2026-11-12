#type spatial
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"
Properties {
    _Tint ("Tint", color) = (1, 1, 1, 0.5)
    _Glow ("Glow", color) = (2, 1, 0.5, 1)
}
struct v2f { vec2 uv0; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA  DepthWrite OFF }
    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv0 = cg_TexCoord0; }
    void fragment(in v2f i, out vec4 fragColor) {
        CG_GLOW(_Glow.rgb * i.uv0.x);
        fragColor = vec4(_Tint.rgb, _Tint.a);
    }
}
Pass { Tags { "LightMode" = "Emissive" } }
