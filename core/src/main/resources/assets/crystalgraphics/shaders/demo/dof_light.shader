// The depth of field demo's points of light (CgDepthOfFieldDemo): an unlit ball whose linear colour is custom0's rgb
// times its alpha, bright enough to blur into the aperture's shape.
#domain surface
Material { ShadingModel Unlit  ColorSpace Linear  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb * CG_OBJECT_CUSTOM0.a;
}
