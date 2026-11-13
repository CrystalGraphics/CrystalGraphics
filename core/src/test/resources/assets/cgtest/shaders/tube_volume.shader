// A material drawing a tube chunk by chunk on a sphere (the TubeVolume factory): CgVfxSlotRendererTest.
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory TubeVolume }

void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(0.0); }
