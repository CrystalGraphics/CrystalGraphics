// CgCollisionDebug's lines and see-through faces: one colour a segment or triangle, carried in cg_Normal with its
// alpha in cg_TexCoord0.x. Depth-tested against the scene and writing no depth, so neither hides what is drawn after
// it; unculled, so a face is seen from inside its shape too. In the HDR scene the colour is decoded but its alpha kept:
// the scene's coverage remap, made for smoke over a bright world, turns a faint tint over a dark one nearly opaque.
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Transparent"

struct v2f { vec4 color; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.color = vec4(cg_Normal, cg_TexCoord0.x);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(CG_LINEAR_SCENE ? cg_SceneDecode(i.color.rgb) : i.color.rgb, i.color.a);
    }
}
