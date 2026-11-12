// docs/MATERIAL_FORMAT.md § 6: sparks that bloom, glow only
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory Sprites }
void surface(in v2f i, inout CgSurface s) { s.emissive = cg_Particle.color.rgb * 6.0; }
