// =============================================================================
// CrystalShader -- hud_image.shader
// An image over a GUI stage (HUD, SCREEN), drawn by CgQuadRenderer: the image times the quad's colour.
// =============================================================================

#type none
#pragma cg_use quad

Tags {
    "RenderType" = "Transparent"
}

Queue = "Overlay"

Properties {
    _MainTex ("Image", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Image"
    }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        Cull OFF
        // Over whatever the GUI left in the host's depth, in submission order.
        DepthTest ALWAYS
        DepthWrite OFF
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv    = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texture(_MainTex, i.uv) * i.color;
    }
}
