// One downsample of Unreal's Gaussian bloom: a stage from the one above it by its high-quality filter, four bilinear
// taps a texel apart (a 4x4 box). One triangle over the stage. CgBloom draws it.
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Target" }
Queue = "Overlay"

Properties {
    _Source ("The stage above", sampler2D) = "black"
}

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
        vec2 texel = 1.0 / vec2(textureSize(_Source, 0));
        vec3 a = texture(_Source, i.uv + texel * vec2(-1.0, 1.0)).rgb;
        vec3 b = texture(_Source, i.uv + texel * vec2(1.0, 1.0)).rgb;
        vec3 c = texture(_Source, i.uv + texel * vec2(-1.0, -1.0)).rgb;
        vec3 d = texture(_Source, i.uv + texel * vec2(1.0, -1.0)).rgb;
        fragColor = vec4((a + b + c + d) * 0.25, 1.0);
    }
}
