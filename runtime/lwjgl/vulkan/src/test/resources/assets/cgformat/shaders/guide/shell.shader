// docs/MATERIAL_FORMAT.md § 5.2: a beam's skin
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Tube }
struct v2f { float along; };
void vertex(inout CgVertex v, out v2f o) {
    v.world += v.normal * 0.1 * cg_Tube.ring.intensity;
    o.along = cg_Tube.ring.arc;
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(fract(i.along)); }
