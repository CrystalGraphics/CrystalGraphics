#type spatial
#pragma cg_feature MOSS
#pragma cg_feature SHAPE_BOX
#pragma cg_feature SHAPE_CYLINDER
#pragma cg_permutation SHAPE_BOX|SHAPE_CYLINDER
#pragma cg_use rows
#pragma cg_use globals
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"
Properties {
    _Tint ("Tint", color) = (1, 1, 1, 1)
    _Rough ("Roughness", Range(0, 1)) = 0.5
}
struct v2f { vec2 uv0; flat int id; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Blend ONE ONE_MINUS_SRC_ALPHA  DepthWrite OFF }
    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv0 = cg_TexCoord0; o.id = CG_VERTEX_ID; }
    void fragment(in v2f i, out vec4 fragColor) {
        vec3 c = _Tint.rgb * _Rough * CG_GLOBAL_directional_light_colour.rgb;
        float a = 1.0;
#ifdef MOSS
        c *= 0.5;
#endif
#ifdef SHAPE_BOX
        a = float(i.id & 1);
#endif
        fragColor = vec4(c, a);
    }
}
