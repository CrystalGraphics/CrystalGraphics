// A tube's chunk drawn on a sphere round it, glowing about its first ring.
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory TubeVolume }
struct v2f { vec3 world; };
void surface(in v2f i, inout CgSurface s) {
    vec3 centre = cg_Tube.origin + cg_Tube.ring.position;
    s.baseColor = vec3(0.2 * cg_Tube.ring.intensity) * exp(-distance(i.world, centre) / max(cg_Tube.ring.radius, 1.0e-3));
}
