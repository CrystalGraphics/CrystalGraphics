// The world's bloom, added onto the target: every Emissive pass drawn into a half-size target, its mip chain blurred
// level by level, and here the blurred levels summed, each wider and fainter than the last (Unreal's bloom: a sum of
// Gaussians of growing size). One triangle over the screen. CgWorldRenderer draws it; nothing else should.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Bloom     ("The blurred chain", sampler2D) = "black"
    _Intensity ("Strength", float) = 1.0
}

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
        // A triangle covering the screen: (-1,-1), (3,-1), (-1,3).
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 c = textureLod(_Bloom, i.uv, 1.0).rgb * 0.30
               + textureLod(_Bloom, i.uv, 2.0).rgb * 0.25
               + textureLod(_Bloom, i.uv, 3.0).rgb * 0.20
               + textureLod(_Bloom, i.uv, 4.0).rgb * 0.15
               + textureLod(_Bloom, i.uv, 5.0).rgb * 0.10;
        fragColor = vec4(c * _Intensity, 0.0);
    }
}
