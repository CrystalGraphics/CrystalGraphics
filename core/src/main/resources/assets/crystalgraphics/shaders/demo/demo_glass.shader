// The lights demo's glass (CgLightsDemo): a tinted pane blended over what is behind it, lit by the world where each of
// its pixels is (cg_Lit, the world lights' translucency volume), with a darker frame round its edge. CG_OBJECT_CUSTOM0 is
// its tint and alpha.
#type spatial

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 local; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        o.local = cg_Position;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // The unit cube's faces, x and y across a pane: a frame where either nears its edge.
        vec2 edge = 0.5 - abs(i.local.xy);
        float frame = 1.0 - smoothstep(0.02, 0.035, min(edge.x, edge.y));
        vec4 tint = CG_OBJECT_CUSTOM0;
        fragColor = vec4(mix(tint.rgb, tint.rgb * 0.35, frame), mix(tint.a, 0.95, frame));
    }
}
