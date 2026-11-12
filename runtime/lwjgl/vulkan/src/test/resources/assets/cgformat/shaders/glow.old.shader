#type spatial
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"
Properties {
    _Glow ("Glow", color) = (1, 1, 1, 1)
    _Strength ("Strength", float) = 1
}
struct v2f { vec2 uv0; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Blend ONE ONE  DepthWrite OFF  ColorMask 0 }
    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv0 = cg_TexCoord0; }
    void fragment(in v2f i, out vec4 fragColor) {
        CG_GLOW(_Glow.rgb * _Strength);
        fragColor = vec4(1.0);
    }
}
Pass { Tags { "LightMode" = "Emissive" } RenderState { Blend ONE ONE  DepthWrite OFF } }
