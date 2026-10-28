// The host's depth copied into a target's own, for a host that lends none (framebuffer 0, multisampled): no colour,
// depth from cg_DepthBuffer. CgWorldRenderer's custom-data pass draws it first.
//
// Written a few 24-bit steps farther: through a float, the copy lands a step off on about 2% of pixels, and a
// redraw of the surface that wrote it would fail its own depth there. What hides behind it is farther than that.
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
        ColorMask 0
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
        float d = texelFetch(cg_DepthBuffer, ivec2(gl_FragCoord.xy), 0).r;
        float farther = cg_DepthParams.x > 0.5 ? -1.0 : 1.0;   // reversed depth counts down to far
        gl_FragDepth = clamp(d + farther * max(4.0 / 16777215.0, abs(d) * 4.8e-7), 0.0, 1.0);
    }
}
