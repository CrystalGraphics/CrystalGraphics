// A glow and nothing else: the mesh blooms in _EmissionColor times _EmissionStrength, and draws nothing into the
// scene. What a halo layer fakes with a soft additive mesh, done as light: it names only s.emissive, so its Forward
// pass writes no colour and no depth. The draw's .emission(fade) scales it.
//
//     CgMaterial glow = CgMaterial.newInstance("crystalgraphics:shaders/emission_only.shader");
//     glow.applyProperties(b -> b.vec4("_EmissionColor", 1f, 0.6f, 0.2f, 1f).set1f("_EmissionStrength", 3f));
//     world.draw(CgMeshShapes.sphere(16, 24), glow).at(x, y, z).emission(fade).submit();
#domain surface
Material { BlendMode Additive  ShadingModel Unlit }

Properties {
    _EmissionColor    ("Glow colour", color) = (1, 1, 1, 1)
    _EmissionStrength ("Glow strength, times the screen's white", float) = 1.0
}

struct v2f { vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    s.emissive = _EmissionColor.rgb * _EmissionStrength;
}
