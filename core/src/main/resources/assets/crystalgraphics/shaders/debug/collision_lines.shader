// CgCollisionDebug's lines: one colour a segment, carried in cg_Normal with its alpha in cg_TexCoord0.x. Depth-tested
// against the scene and writing no depth, so a line never hides another drawn after it.
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
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
        fragColor = i.color;
    }
}
