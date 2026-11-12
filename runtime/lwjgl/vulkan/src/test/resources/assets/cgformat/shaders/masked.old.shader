#type spatial
Tags { "RenderType" = "Opaque" "Depth" = "Clip" }
Queue = "AlphaTest"
Properties { _Leaves ("Leaves", sampler2D) = "white" }
struct v2f { vec2 uv0; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Cull OFF }
    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv0 = cg_TexCoord0; }
    void fragment(in v2f i, out vec4 fragColor) {
        vec4 t = texture(_Leaves, i.uv0);
        cg_Clip(t.a);
        fragColor = vec4(t.rgb, 1.0);
    }
}
