// The host's depth copied into a target's own, for a host that lends none (framebuffer 0, multisampled): colour
// written 0, depth from cg_DepthBuffer. CgWorldRenderer's custom-data pass draws it first.
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "Media" = "Off" }
Queue = "Overlay"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite ON
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
        gl_FragDepth = texelFetch(cg_DepthBuffer, ivec2(gl_FragCoord.xy), 0).r;
    }
}
