// A glow and nothing else: the mesh blooms in _EmissionColor times _EmissionStrength, and draws nothing into the
// scene. What a halo layer fakes with a soft additive mesh, done as light. Its Forward pass writes no
// colour and no depth, there only because a draw needs one.
//
//     CgMaterial glow = CgMaterial.newInstance("crystalgraphics:shaders/emission_only.shader");
//     glow.applyProperties(b -> b.vec4("_EmissionColor", 1f, 0.6f, 0.2f, 1f).set1f("_EmissionStrength", 3f));
//     world.draw(CgMeshShapes.sphere(16, 24), glow).at(x, y, z).emission(fade).submit();
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _EmissionColor    ("Glow colour", color) = (1, 1, 1, 1)
    _EmissionStrength ("Glow strength, times the screen's white", float) = 1.0
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        ColorMask 0
        DepthWrite OFF
        DepthTest LEQUAL
        Blend OFF
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(CG_EMISSION, 0.0);
    }
}
