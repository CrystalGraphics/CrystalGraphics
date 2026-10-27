// CustomStencil's copy into CustomDepth's R8 colour (CgWorldRenderer.recordCustomDepth): a full-screen draw a stencil
// bit, its state testing that bit, adding the bit's value (CG_OBJECT_CUSTOM0.x, 2^b / 255). Unreal copies the stencil
// into a texture where a shader samples it on GL (CustomDepthRendering.cpp:436-450); no device here samples a stencil.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" "Media" = "Off" }
Queue = "Transparent"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
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
        fragColor = vec4(CG_OBJECT_CUSTOM0.x, 0.0, 0.0, 0.0);
    }
}
