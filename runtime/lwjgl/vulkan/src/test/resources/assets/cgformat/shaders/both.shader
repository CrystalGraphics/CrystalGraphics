// Meshes and particles both: Mesh draws the static and skeletal meshes, MeshParticles every particle draw, tinted by
// the particle's colour.
#domain surface
Material { BlendMode Translucent  VertexFactory Mesh, MeshParticles }
Properties { _MainTex ("Texture", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    vec4 tint = vec4(1.0);
#if defined(FX_ON_MESH_PARTICLES) || defined(FX_ON_SPRITES) || defined(FX_ON_RIBBONS)
    tint = cg_Particle.color;
#endif
    vec4 c = texture(_MainTex, i.uv0) * tint;
    s.baseColor = c.rgb;
    s.opacity = c.a;
}
