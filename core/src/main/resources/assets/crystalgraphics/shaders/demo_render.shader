// ─────────────────────────────────────────────────────────────────────────────
// demo_render.shader — minimal lit shader used by CgRenderDemo.
//
// Per-cube colour is read from CG_OBJECT_CUSTOM0.rgb (set via cmd.custom0 on
// each CgRenderCommand). No Properties block needed — all appearance data
// comes through the per-instance buffer.
// ─────────────────────────────────────────────────────────────────────────────
#type spatial

Tags  { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 normalWs; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest  LEQUAL
        DepthWrite ON
        Cull       BACK
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.normalWs  = CG_NORMAL_MATRIX * cg_Normal;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3  n     = normalize(i.normalWs);
        float ndotl = max(dot(n, normalize(vec3(1.0, 2.0, 1.0))), 0.0);
        vec3  col   = CG_OBJECT_CUSTOM0.rgb * (0.2 + 0.8 * ndotl);
        fragColor   = vec4(col, 1.0);
    }
}
