// Drawn by a mod's own factory, read from its file: cgformat:shaders/factories/Wobble.factory.
#domain surface
Material { VertexFactory cgformat:Wobble }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(0.5 + 0.5 * sin(cg_Wobble.phase)) * vec3(i.uv0, 1.0); }
