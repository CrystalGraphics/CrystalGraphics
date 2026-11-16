// The depth of field demo's rings (CgDepthOfFieldDemo): an added ring of light in custom0's colour on a quad, at the
// default BeforeDOF so it blurs with the scene; the demo moves its other two instances later, which stay sharp.
#domain surface
Material { BlendMode Additive  TranslucencyPass BeforeDOF  ShadingModel Unlit  Faces Both  ColorSpace Linear  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    float r = length(i.uv * 2.0 - 1.0);
    float ring = smoothstep(0.62, 0.7, r) * (1.0 - smoothstep(0.86, 0.94, r));
    float cross = (1.0 - smoothstep(0.02, 0.05, min(abs(i.uv.x - 0.5), abs(i.uv.y - 0.5)))) * step(r, 0.62);
    s.emissive = CG_OBJECT_CUSTOM0.rgb * CG_OBJECT_CUSTOM0.a * max(ring, cross * 0.7);
    s.opacity = max(ring, cross);
}
