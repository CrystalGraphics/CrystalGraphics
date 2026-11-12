#type spatial
Tags { "RenderType" = "Opaque" }
Queue = "Geometry"
Properties {
    _Amp ("Wave height, blocks", float) = 0.2
    _Water ("Water", color) = (0.1, 0.3, 0.6, 1)
}
struct v2f { vec2 uv0; };
Pass {
    Tags { "LightMode" = "Forward" }
    void vertex(out v2f o) {
        vec4 w = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        w.y += sin(CG_TIME + cg_Position.x * 2.0) * _Amp;
        o.uv0 = cg_TexCoord0;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * w;
    }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Water.rgb * (0.8 + 0.2 * fwidth(i.uv0.x)), 1.0); }
}
