// F2: keywords and a one-of, per-draw values, a global parameter, parameter marks and a flat field
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  PerDrawValues On }
Keywords {
    MOSS ("Moss on top")
    SHAPE ("Region shape") = BOX | CYLINDER
}
Properties {
    _Tint ("Tint", color, hdr) = (1, 1, 1, 1)
    _Rough ("Roughness", float, range(0, 1)) = 0.5
}
struct v2f { vec2 uv0; flat int id; };
void vertex(inout CgVertex v, out v2f o) {
    o.uv0 = v.uv0;
    o.id = CG_VERTEX_ID;
}
void surface(in v2f i, inout CgSurface s) {
    s.baseColor = _Tint.rgb * _Rough * CG_GLOBAL_directional_light_colour.rgb;
#ifdef MOSS
    s.baseColor *= 0.5;
#endif
#ifdef SHAPE_BOX
    s.opacity = float(i.id & 1);
#endif
}
