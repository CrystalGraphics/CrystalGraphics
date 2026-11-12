#type spatial
Tags { "RenderType" = "Opaque" }
Queue = "Geometry"
Properties { _Albedo ("Albedo", sampler2D) = "white" }
struct v2f { vec2 uv0; };
Pass {
    Tags { "LightMode" = "Forward" }
    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv0 = cg_TexCoord0; }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(texture(_Albedo, i.uv0).rgb, 1.0); }
}
