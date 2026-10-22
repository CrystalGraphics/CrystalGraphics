// The host's depth before our opaque draws, copied as it is: a pixel nearer than it afterwards is one of our objects'
// (CgWorldLights' shadows: what casts on Minecraft's world, and which pixels are lit as ours).
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
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
        fragColor = vec4(texelFetch(cg_DepthBuffer, ivec2(gl_FragCoord.xy), 0).r, 0.0, 0.0, 1.0);
    }
}
