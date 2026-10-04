// A depth pyramid's first level (CgGpuOps.depthPyramid): the scene's depth at each texel as eye distance, whatever the
// host's depth convention, so the cull compares eye depths. Read texel for texel: a filtered depth would blend an edge
// into a depth nothing there has. One triangle over the target; CgGpuOps draws it, nothing else should.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ZERO
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float depth = texelFetch(cg_DepthBuffer, ivec2(gl_FragCoord.xy), 0).r;
        fragColor = vec4(cg_LinearEyeDepth(depth), 0.0, 0.0, 1.0);
    }
}
